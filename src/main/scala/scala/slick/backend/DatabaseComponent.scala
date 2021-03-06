package scala.slick.backend

import java.util.concurrent.atomic.AtomicLong

import scala.language.existentials

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Try, Success, Failure, DynamicVariable}
import scala.util.control.NonFatal
import java.io.Closeable

import org.slf4j.LoggerFactory
import org.reactivestreams._

import scala.slick.SlickException
import scala.slick.action._
import scala.slick.util.{GlobalConfig, DumpInfo, TreeDump, SlickLogger, ignoreFollowOnError}

/** Backend for the basic database and session handling features.
  * Concrete backends like `JdbcBackend` extend this type and provide concrete
  * types for `Database`, `DatabaseFactory` and `Session`. */
trait DatabaseComponent { self =>
  protected lazy val actionLogger = new SlickLogger(LoggerFactory.getLogger(classOf[DatabaseComponent].getName+".action"))
  protected lazy val streamLogger = new SlickLogger(LoggerFactory.getLogger(classOf[DatabaseComponent].getName+".stream"))

  type This >: this.type <: DatabaseComponent
  /** The type of database objects used by this backend. */
  type Database <: DatabaseDef
  /** The type of the database factory used by this backend. */
  type DatabaseFactory <: DatabaseFactoryDef
  /** The type of session objects used by this backend. */
  type Session >: Null <: SessionDef
  /** The action effects supported by this backend. */
  type Effects <: Effect.Read

  /** The database factory */
  val Database: DatabaseFactory

  /** A database instance to which connections can be created. */
  trait DatabaseDef { this: Database =>
    /** Create a new session. The session needs to be closed explicitly by calling its close() method. */
    def createSession(): Session

    /** Free all resources allocated by Slick for this Database. */
    def close(): Unit

    /** Run an Action asynchronously and return the result as a Future. */
    final def run[R](a: Action[Effects, R, NoStream]): Future[R] = runInternal(a, false)

    private[slick] final def runInternal[R](a: Action[Effects, R, NoStream], useSameThread: Boolean): Future[R] =
      runInContext(a, new DatabaseActionContext(useSameThread), false)

    /** Create a `Publisher` for Reactive Streams which, when subscribed to, will run the specified
      * Action and return the result directly as a stream without buffering everything first. This
      * method is only supported for streaming Actions.
      *
      * The Publisher itself is just a stub that holds a reference to the Action and this Database.
      * The Action does not actually start to run until the call to `onSubscribe` returns, after
      * which the Subscriber is responsible for reading the full response or cancelling the
      * Subscription. The created Publisher will only serve a single Subscriber and cannot be
      * reused (because multiple runs of an Action can produce different results, which is not
      * allowed for a Publisher).
      *
      * For the purpose of combinators such as `andFinally` which can run after a stream has been
      * produced, consuming the stream is always considered to be successful, even when cancelled
      * by the Subscriber. For example, there is no way for the Subscriber to cause a rollback when
      * streaming the results of `someQuery.result.transactionally`.
      *
      * When using a JDBC back-end, all `onNext` calls are done synchronously and the ResultSet row
      * is not advanced before `onNext` returns. This allows the Subscriber to access LOB pointers
      * from within `onNext`. If streaming is interrupted due to back-pressure signaling, the next
      * row will be prefetched (in order to buffer the next result page from the server when a page
      * boundary has been reached). */
    final def stream[T](a: Action[Effects, _, Streaming[T]]): DatabasePublisher[T] = streamInternal(a, false)

    private[slick] final def streamInternal[T](a: Action[Effects, _, Streaming[T]], useSameThread: Boolean): DatabasePublisher[T] =
      createPublisher(a, s => createStreamingDatabaseActionContext(s, useSameThread))

    /** Create a Reactive Streams `Publisher` using the given context factory. */
    protected[this] def createPublisher[T](a: Action[Effects, _, Streaming[T]], createCtx: Subscriber[_ >: T] => StreamingDatabaseActionContext): DatabasePublisher[T] = new DatabasePublisherSupport[T] {
      def subscribe(s: Subscriber[_ >: T]) = if(allowSubscriber(s)) {
        val ctx = createCtx(s)
        val subscribed = try { s.onSubscribe(ctx); true } catch {
          case NonFatal(ex) =>
            streamLogger.warn("Subscriber.onSubscribe failed unexpectedly", ex)
            false
        }
        if(subscribed) {
          try {
            runInContext(a, ctx, true).onComplete {
              case Success(_) => ctx.tryOnComplete
              case Failure(t) => ctx.tryOnError(t)
            }(Action.sameThreadExecutionContext)
          } catch {
            case NonFatal(ex) =>
              streamLogger.warn("Database.streamInContext failed unexpectedly", ex)
              ctx.tryOnError(ex)
          }
        }
      }
    }

    /** Create the default StreamingDatabaseActionContext for this backend. */
    protected[this] def createStreamingDatabaseActionContext[T](s: Subscriber[_ >: T], useSameThread: Boolean): StreamingDatabaseActionContext =
      new StreamingDatabaseActionContext(s, useSameThread, DatabaseDef.this)

    /** Run an Action in an existing DatabaseActionContext. This method can be overridden in
      * subclasses to support new DatabaseActions which cannot be expressed through
      * SynchronousDatabaseAction.
      *
      * @param streaming Whether to return the result as a stream. In this case, the context must
      *                  be a `StreamingDatabaseActionContext` and the Future result should be
      *                  completed with `null` or failed after streaming has finished. This
      *                  method should not call any `Subscriber` method other than `onNext`. */
    protected[this] def runInContext[R](a: Action[Effects, R, NoStream], ctx: DatabaseActionContext, streaming: Boolean): Future[R] = {
      logAction(a, ctx)
      a match {
        case SuccessAction(v) => Future.successful(v)
        case FailureAction(t) => Future.failed(t)
        case FutureAction(f) => f
        case FlatMapAction(base, f, ec) =>
          runInContext(base, ctx, false).flatMap(v => runInContext(f(v), ctx, streaming))(ctx.getEC(ec))
        case AndThenAction(a1, a2) =>
          runInContext(a1, ctx, false).flatMap(_ => runInContext(a2, ctx, streaming))(Action.sameThreadExecutionContext)
        case ZipAction(a1, a2) =>
          runInContext(a1, ctx, false).flatMap { r1 =>
            runInContext(a2, ctx, false).map { r2 =>
              (r1, r2)
            }(Action.sameThreadExecutionContext)
          }(Action.sameThreadExecutionContext).asInstanceOf[Future[R]]
        case CleanUpAction(base, f, keepFailure, ec) =>
          val p = Promise[R]()
          runInContext(base, ctx, streaming).onComplete { t1 =>
            try {
              val a2 = f(t1 match {
                case Success(_) => None
                case Failure(t) => Some(t)
              })
              runInContext(a2, ctx, false).onComplete { t2 =>
                if(t2.isFailure && (t1.isSuccess || !keepFailure)) p.complete(t2.asInstanceOf[Failure[R]])
                else p.complete(t1)
              } (Action.sameThreadExecutionContext)
            } catch {
              case NonFatal(ex) =>
                throw (t1 match {
                  case Failure(t) if keepFailure => t
                  case _ => ex
                })
            }
          } (ctx.getEC(ec))
          p.future
        case FailedAction(a) =>
          runInContext(a, ctx, false).failed.asInstanceOf[Future[R]]
        case AsTryAction(a) =>
          val p = Promise[R]()
          runInContext(a, ctx, false).onComplete(v => p.success(v.asInstanceOf[R]))(Action.sameThreadExecutionContext)
          p.future
        case NamedAction(a, _) =>
          runInContext(a, ctx, streaming)
        case a: SynchronousDatabaseAction[_, _, _, _] =>
          if(streaming) {
            if(a.supportsStreaming) streamSynchronousDatabaseAction(a.asInstanceOf[SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream]], ctx.asInstanceOf[StreamingDatabaseActionContext]).asInstanceOf[Future[R]]
            else runInContext(CleanUpAction(AndThenAction(Action.Pin, a.nonFusedEquivalentAction), _ => Action.Unpin, true, Action.sameThreadExecutionContext), ctx, streaming)
          } else runSynchronousDatabaseAction(a.asInstanceOf[SynchronousDatabaseAction[This, _, R, NoStream]], ctx)
        case a: DatabaseAction[_, _, _] =>
          throw new SlickException(s"Unsupported database action $a for $this")
      }
    }

    /** Within a synchronous execution, ensure that a Session is available. */
    protected[this] final def acquireSession(ctx: DatabaseActionContext): Unit =
      if(!ctx.isPinned) ctx.currentSession = createSession()

    /** Within a synchronous execution, close the current Session unless it is pinned.
      *
      * @param discardErrors If set to true, swallow all non-fatal errors that arise while
      *        closing the Session. */
    protected[this] final def releaseSession(ctx: DatabaseActionContext, discardErrors: Boolean): Unit =
      if(!ctx.isPinned) {
        try ctx.currentSession.close() catch { case NonFatal(ex) if(discardErrors) => }
        ctx.currentSession = null
      }

    /** Run a `SynchronousDatabaseAction` on this database. */
    protected[this] def runSynchronousDatabaseAction[R](a: SynchronousDatabaseAction[This, _, R, NoStream], ctx: DatabaseActionContext): Future[R] = {
      val promise = Promise[R]()
      ctx.getEC(synchronousExecutionContext).prepare.execute(new Runnable {
        def run: Unit =
          try {
            ctx.sync
            val res = try {
              acquireSession(ctx)
              val res = try a.run(ctx) catch { case NonFatal(ex) =>
                releaseSession(ctx, true)
                throw ex
              }
              releaseSession(ctx, false)
              res
            } finally { ctx.sync = 0 }
            promise.success(res)
          } catch { case NonFatal(ex) => promise.failure(ex) }
      })
      promise.future
    }

    /** Stream a `SynchronousDatabaseAction` on this database. */
    protected[this] def streamSynchronousDatabaseAction(a: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream], ctx: StreamingDatabaseActionContext): Future[Null] = {
      ctx.streamingAction = a
      scheduleSynchronousStreaming(a, ctx)(null)
      ctx.streamingResultPromise.future
    }

    /** Stream a part of the results of a `SynchronousDatabaseAction` on this database. */
    protected[DatabaseComponent] def scheduleSynchronousStreaming(a: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream], ctx: StreamingDatabaseActionContext)(initialState: a.StreamState): Unit =
      ctx.getEC(synchronousExecutionContext).prepare.execute(new Runnable {
        private[this] def str(l: Long) = if(l != Long.MaxValue) l else if(GlobalConfig.unicodeDump) "\u221E" else "oo"

        def run: Unit = try {
          val debug = streamLogger.isDebugEnabled
          var state = initialState
          ctx.sync
          if(state eq null) acquireSession(ctx)
          var demand = ctx.demandBatch
          var realDemand = if(demand < 0) demand - Long.MinValue else demand
          do {
            try {
              if(debug)
                streamLogger.debug((if(state eq null) "Starting initial" else "Restarting ") + " streaming action, realDemand = " + str(realDemand))
              if(ctx.cancelled) {
                if(ctx.deferredError ne null) throw ctx.deferredError
                if(state ne null) { // streaming cancelled before finishing
                  val oldState = state
                  state = null
                  a.cancelStream(ctx, oldState)
                }
              } else if((realDemand > 0 || (state eq null))) {
                val oldState = state
                state = null
                state = a.emitStream(ctx, realDemand, oldState)
              }
              if(state eq null) { // streaming finished and cleaned up
                releaseSession(ctx, true)
                ctx.streamingResultPromise.success(null)
              }
            } catch { case NonFatal(ex) =>
              if(state ne null) try a.cancelStream(ctx, state) catch ignoreFollowOnError
              releaseSession(ctx, true)
              throw ex
            } finally {
              ctx.streamState = state
              ctx.sync = 0
            }
            if(debug) {
              if(state eq null) streamLogger.debug(s"Sent up to ${str(realDemand)} elements - Stream " + (if(ctx.cancelled) "cancelled" else "completely delivered"))
              else streamLogger.debug(s"Sent ${str(realDemand)} elements, more available - Performing atomic state transition")
            }
            demand = ctx.delivered(demand)
            realDemand = demand
          } while ((state ne null) && demand > 0)
          if(streamLogger.isDebugEnabled) {
            if(state ne null) streamLogger.debug("Suspending streaming action with continuation (more data available)")
            else streamLogger.debug("Finished streaming action")
          }
        } catch { case NonFatal(ex) => ctx.streamingResultPromise.failure(ex) }
      })


    /** Return the default ExecutionContet for this Database which should be used for running
      * SynchronousDatabaseActions for asynchronous execution. */
    protected[this] def synchronousExecutionContext: ExecutionContext

    protected[this] def logAction(a: Action[_ <: Effect, _, _ <: NoStream], ctx: DatabaseActionContext): Unit = {
      if(actionLogger.isDebugEnabled && a.isLogged) {
        ctx.sequenceCounter += 1
        val logA = a.nonFusedEquivalentAction
        val aPrefix = if(a eq logA) "" else "[fused] "
        val dump = TreeDump.get(logA, prefix = "    ", firstPrefix = aPrefix, narrow = {
          case a: Action[_, _, _] => a.nonFusedEquivalentAction
          case o => o
        })
        val msg = DumpInfo.highlight("#" + ctx.sequenceCounter) + ": " + dump.substring(0, dump.length-1)
        actionLogger.debug(msg)
      }
    }

    /** Run the supplied function with a new session and automatically close the session at the end.
      * Exceptions thrown while closing the session are propagated, but only if the code block using the
      * session terminated normally. Otherwise the first exception wins. */
    def withSession[T](f: Session => T): T = {
      val s = createSession()
      var ok = false
      try {
        val res = f(s)
        ok = true
        res
      } finally {
        if(ok) s.close() // Let exceptions propagate normally
        else {
          // f(s) threw an exception, so don't replace it with an Exception from close()
          try s.close() catch { case _: Throwable => }
        }
      }
    }

    /** Run the supplied thunk with a new session and automatically close the
      * session at the end.
      * The session is stored in a dynamic (inheritable thread-local) variable
      * which can be accessed with the implicit function in
      * Database.dynamicSession. */
    def withDynSession[T](f: => T): T = withSession { s: Session => withDynamicSession(s)(f) }

    /** Run the supplied function with a new session in a transaction and automatically close the session at the end. */
    def withTransaction[T](f: Session => T): T = withSession { s => s.withTransaction(f(s)) }

    /** Run the supplied thunk with a new session in a transaction and
      * automatically close the session at the end.
      * The session is stored in a dynamic (inheritable thread-local) variable
      * which can be accessed with the implicit function in
      * Database.dynamicSession. */
    def withDynTransaction[T](f: => T): T = withDynSession { Database.dynamicSession.withTransaction(f) }
  }

  private[this] val dyn = new DynamicVariable[Session](null)

  /** Run a block of code with the specified `Session` bound to the thread-local `dynamicSession`. */
  protected def withDynamicSession[T](s: Session)(f: => T): T = dyn.withValue(s)(f)

  /** Factory methods for creating `Database` instances. */
  trait DatabaseFactoryDef {
    /** An implicit function that returns the thread-local session in a withSession block. */
    implicit def dynamicSession: Session = {
      val s = dyn.value
      if(s eq null)
        throw new SlickException("No implicit session available; dynamicSession can only be used within a withDynSession block")
      else s
    }
  }

  /** A logical session of a `Database`. The underlying database connection is created lazily on demand. */
  trait SessionDef extends Closeable {
    /** Close this Session. */
    def close(): Unit

    /** Call this method within a `withTransaction` call to roll back the current
      * transaction after `withTransaction` returns. */
    def rollback(): Unit

    /** Run the supplied function within a transaction. If the function throws an Exception
      * or the session's `rollback()` method is called, the transaction is rolled back,
      * otherwise it is committed when the function returns. */
    def withTransaction[T](f: => T): T

    /** Use this Session as the `dynamicSession` for running the supplied thunk. */
    def asDynamicSession[T](f: => T): T = withDynamicSession[T](this.asInstanceOf[Session])(f)

    /** Force an actual database session to be opened. Slick sessions are lazy, so you do not
      * get a real database connection until you need it or you call force() on the session. */
    def force(): Unit
  }

  /** The context object passed to database actions by the execution engine.
    *
    * @param useSameThread Whether to run all operations on the current thread or schedule
    *   them normally on the appropriate ExecutionContext. This is used by the blocking API. */
  protected[this] class DatabaseActionContext(val useSameThread: Boolean) extends ActionContext[This] {
    /** Return the specified ExecutionContext unless running in same-thread mode, in which case
      * `Action.sameThreadExecutionContext` is returned instead. */
    def getEC(ec: ExecutionContext): ExecutionContext =
      if(useSameThread) Action.sameThreadExecutionContext else ec

    /** A volatile variable to enforce the happens-before relationship (see
      * [[https://docs.oracle.com/javase/specs/jls/se7/html/jls-17.html]] and
      * [[http://gee.cs.oswego.edu/dl/jmm/cookbook.html]]) when executing something in
      * a synchronous action context. It is read when entering the context and written when leaving
      * so that all writes to non-volatile variables within the context are visible to the next
      * synchronous execution. */
    @volatile private[DatabaseComponent] var sync = 0

    private[DatabaseComponent] var currentSession: Session = null

    /** Used for the sequence counter in Action debug output. This variable is volatile because it
      * is only updated sequentially but not protected by a synchronous action context. */
    @volatile private[DatabaseComponent] var sequenceCounter = 0

    def session: Session = currentSession
  }

  /** A special DatabaseActionContext for streaming execution. */
  protected[this] class StreamingDatabaseActionContext(subscriber: Subscriber[_], useSameThread: Boolean, database: Database) extends DatabaseActionContext(useSameThread) with StreamingActionContext[This] with Subscription {
    /** Whether the Subscriber has been signaled with `onComplete` or `onError`. */
    private[this] var finished = false

    /** The total number of elements requested and not yet marked as delivered by the synchronous
      * streaming action. Whenever this value drops to 0, streaming is suspended. When it is raised
      * up from 0 in `request`, streaming is scheduled to be restarted. It is initially set to
      * `Long.MinValue` when streaming starts. Any negative value above `Long.MinValue` indicates
      * the actual demand at that point. It is reset to 0 when the initial streaming ends. */
    private[this] val remaining = new AtomicLong(Long.MinValue)

    /** The number of remaining elements that are not in the current batch. Unlike `remaining`,
      * which is decremented at the end of the batch, this is decremented at the beginning. It is
      * only used for overflow detection according to Reactive Streams spec, 3.17. */
    private[this] val remainingNotInBatch = new AtomicLong(0L)

    /** An error that will be signaled to the Subscriber when the stream is cancelled or
      * terminated. This is used for signaling demand overflow in `request()` while guaranteeing
      * that the `onError` message does not overlap with an active `onNext` call. */
    private[DatabaseComponent] var deferredError: Throwable = null

    /** The state for a suspended streaming action. Must only be set from a synchronous action
      * context. */
    private[DatabaseComponent] var streamState: AnyRef = null

    /** The streaming action which may need to be continued with the suspended state */
    private[DatabaseComponent] var streamingAction: SynchronousDatabaseAction[This, _ <: Effect, _, _ <: NoStream] = null

    @volatile private[this] var cancelRequested = false

    /** The Promise to complete when streaming has finished. */
    val streamingResultPromise = Promise[Null]()

    /** Indicate that the specified number of elements has been delivered. Returns the remaining
      * demand. This is an atomic operation. It must only be called from the synchronous action
      * context which performs the streaming. */
    def delivered(num: Long): Long = remaining.addAndGet(-num)

    /** Get the current demand that has not yet been marked as delivered and mark it as being in
      * the current batch. When this value is negative, the initial streaming action is still
      * running and the real demand can be computed by subtracting `Long.MinValue` from the
      * returned value. */
    def demandBatch: Long = {
      val demand = remaining.get()
      val realDemand = if(demand < 0L) demand - Long.MinValue else demand
      remainingNotInBatch.addAndGet(-realDemand)
      demand
    }

    /** Whether the stream has been cancelled by the Subscriber */
    def cancelled: Boolean = cancelRequested

    def emit(v: Any): Unit = subscriber.asInstanceOf[Subscriber[Any]].onNext(v)

    /** Finish the stream with `onComplete` if it is not finished yet. May only be called from a
      * synchronous action context. */
    def tryOnComplete: Unit = if(!finished && !cancelRequested) {
      if(streamLogger.isDebugEnabled) streamLogger.debug("Signaling onComplete()")
      finished = true
      try subscriber.onComplete() catch {
        case NonFatal(ex) => streamLogger.warn("Subscriber.onComplete failed unexpectedly", ex)
      }
    }

    /** Finish the stream with `onError` if it is not finished yet. May only be called from a
      * synchronous action context. */
    def tryOnError(t: Throwable): Unit = if(!finished) {
      if(streamLogger.isDebugEnabled) streamLogger.debug(s"Signaling onError($t)")
      finished = true
      try subscriber.onError(t) catch {
        case NonFatal(ex) => streamLogger.warn("Subscriber.onError failed unexpectedly", ex)
      }
    }

    /** Restart a suspended streaming action. Must only be called from the Subscriber context. */
    def restartStreaming: Unit = {
      sync
      val s = streamState
      if(s ne null) {
        streamState = null
        if(streamLogger.isDebugEnabled) streamLogger.debug("Scheduling stream continuation after transition from demand = 0")
        val a = streamingAction
        database.scheduleSynchronousStreaming(a, this)(s.asInstanceOf[a.StreamState])
      } else {
        if(streamLogger.isDebugEnabled) streamLogger.debug("Saw transition from demand = 0, but no stream continuation available")
      }
    }

    ////////////////////////////////////////////////////////////////////////// Subscription methods

    def request(l: Long): Unit = if(!cancelRequested) {
      if(l <= 0) {
        deferredError = new IllegalArgumentException("Requested count must not be <= 0 (see Reactive Streams spec, 3.9)")
        cancel
      } else if(remainingNotInBatch.addAndGet(l) < 0) {
        deferredError = new IllegalStateException("Pending element count must not exceed 2^63-1 (see Reactive Streams spec, 3.17)")
        cancel
      } else {
        if(!cancelRequested && remaining.getAndAdd(l) == 0L) restartStreaming
      }
    }

    def cancel: Unit = if(!cancelRequested) {
      cancelRequested = true
      // Restart streaming because cancelling requires closing the result set and the session from
      // within a synchronous action context. This will also complete the result Promise and thus
      // allow the rest of the scheduled Action to run.
      if(remaining.getAndSet(Long.MaxValue) == 0L) restartStreaming
    }
  }
}
