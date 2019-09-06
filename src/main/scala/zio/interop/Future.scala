package zio

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }
import scala.reflect.ClassTag
import zio.internal.PlatformLive
import zio.internal.tracing.TracingConfig

package object interop {

  /**
   * A [[scala.concurrent.Future]] is a running computation, and corresponds
   * most closely to ZIO `Fiber`.
   */
  type Future[+A] = Fiber[Throwable, A]

  private val Global = ExecutionContext.Implicits.global

  private final def unsafeRun[E, A](ec: ExecutionContext, io: IO[E, A]): A =
    Runtime[Any]((), PlatformLive.fromExecutionContext(ec).withTracingConfig(TracingConfig.disabled)).unsafeRun(io)

  private final def toTry[A](e: Either[Throwable, A]): Try[A] =
    e.fold(Failure(_), Success(_))

  /**
   * An API-compatible implementation of [[scala.concurrent.Future]], which
   * is backed by ZIO. While this structure is not performant, due to emulation
   * of the `Future` API, it can be useful to help migrate legacy code away
   * from `Future` and to ZIO.
   * */
  object Future {
    final val never: Future[Nothing] = Fiber.never

    final val unit: Future[Unit] = Fiber.unit

    final def failed[T](exception: Throwable): Future[T] =
      Fiber.fail(exception)

    final def successful[T](result: T): Future[T] =
      Fiber.succeed(result)

    final def fromTry[T](result: Try[T]): Future[T] =
      result match {
        case Failure(t) => failed(t)
        case Success(v) => successful(v)
      }

    final def apply[T](body: => T)(implicit ec: ExecutionContext): Future[T] =
      unsafeRun(ec, IO.effect(body).fork)

    final def sequence[A](in: List[Future[A]])(implicit ec: ExecutionContext): Future[List[A]] =
      unsafeRun(ec, IO.collectAll(in.map(_.join)).fork)

    final def sequence[A](in: Vector[Future[A]])(implicit ec: ExecutionContext): Future[Vector[A]] =
      unsafeRun(ec, IO.collectAll(in.map(_.join)).map(_.toVector).fork)

    final def sequence[A](in: Seq[Future[A]])(implicit ec: ExecutionContext): Future[Seq[A]] =
      unsafeRun(ec, IO.collectAll(in.map(_.join)).map(_.toSeq).fork)

    final def firstCompletedOf[T](futures: Iterable[Future[T]])(implicit ec: ExecutionContext): Future[T] =
      unsafeRun(ec, IO.absolve(IO.raceAll(IO.interrupt, futures.map(_.join.either))).fork)

    final def find[T](futures: Iterable[Future[T]])(p: T => Boolean)(implicit ec: ExecutionContext): Future[Option[T]] =
      unsafeRun(
        ec,
        (futures.foldLeft[IO[Throwable, Option[T]]](IO.interrupt) {
          case (acc, future) =>
            acc orElse future.join.flatMap(t => if (p(t)) IO.succeed(t) else IO.interrupt).map(Some(_))
        } orElse IO.succeed(None)).fork
      )

    final def foldLeft[T, R](
      futures: Iterable[Future[T]]
    )(zero: R)(op: (R, T) => R)(implicit ec: ExecutionContext): Future[R] =
      unsafeRun(
        ec,
        futures
          .foldLeft[IO[Throwable, R]](IO.succeed(zero)) {
            case (acc, future) =>
              acc.flatMap(r => future.join.map(op(r, _)))
          }
          .fork
      )

    final def fold[T, R](
      futures: Iterable[Future[T]]
    )(zero: R)(op: (R, T) => R)(implicit ec: ExecutionContext): Future[R] =
      foldLeft(futures)(zero)(op)

    final def reduce[T, R >: T](
      futures: Iterable[Future[T]]
    )(op: (R, T) => R)(implicit ec: ExecutionContext): Future[R] =
      futures.headOption match {
        case None => Fiber.interrupt
        case Some(t) =>
          val ts = futures.tail

          unsafeRun(ec, t.join.map(t => fold[T, R](ts)(t)(op)))
      }

    final def reduceLeft[T, R >: T](
      futures: Iterable[Future[T]]
    )(op: (R, T) => R)(implicit ec: ExecutionContext): Future[R] =
      reduce[T, R](futures)(op)

    final def traverse[A, B](in: List[A])(fn: A => Future[B])(implicit ec: ExecutionContext): Future[List[B]] =
      unsafeRun(ec, IO.foreach(in)(a => fn(a).join).fork)

    final def traverse[A, B](in: Vector[A])(fn: A => Future[B])(implicit ec: ExecutionContext): Future[Vector[B]] =
      unsafeRun(ec, IO.foreach(in)(a => fn(a).join).map(_.toVector).fork)

    final def traverse[A, B](in: Seq[A])(fn: A => Future[B])(implicit ec: ExecutionContext): Future[Seq[B]] =
      unsafeRun(ec, IO.foreach(in)(a => fn(a).join).map(_.toSeq).fork)
  }

  implicit class FutureSyntax[T](val value: Future[T]) extends AnyVal {
    final def onSuccess[U](pf: PartialFunction[T, U])(implicit ec: ExecutionContext): Unit =
      unsafeRun(ec, value.join.flatMap[Any, Throwable, Option[U]](t => IO.effect(pf lift t)).fork.unit)

    final def onFailure[U](pf: PartialFunction[Throwable, U])(implicit ec: ExecutionContext): Unit =
      unsafeRun(ec, value.join.either.flatMap {
        case Left(t)  => IO.effect(pf lift t)
        case Right(_) => IO.unit
      }.fork.unit)

    final def onComplete[U](f: Try[T] => U)(implicit ec: ExecutionContext): Unit =
      unsafeRun(ec, value.join.either.map(toTry(_)).flatMap[Any, Throwable, U](t => IO.effect(f(t))).fork.unit)

    final def isCompleted: Boolean =
      unsafeRun(Global, value.poll.map(_.fold(false)(_ => true)))

    final def failed: Future[Throwable] =
      unsafeRun(Global, value.join.flip.catchAll[Any, Nothing, Throwable](_ => IO.interrupt).fork)

    final def foreach[U](f: T => U)(implicit ec: ExecutionContext): Unit =
      onSuccess { case t => f(t) }

    final def transform[S](s: T => S, f: Throwable => Throwable)(implicit ec: ExecutionContext): Future[S] =
      unsafeRun(ec, value.join.bimap(f, s).fork)

    final def transform[S](f: Try[T] => Try[S])(implicit ec: ExecutionContext): Future[S] = {
      val g: Try[T] => IO[Throwable, S] =
        (t: Try[T]) =>
          IO.effect(f(t) match {
              case Failure(t) => IO.fail(t)
              case Success(s) => IO.succeed(s)
            })
            .flatten

      unsafeRun(ec, value.join.either.map(toTry(_)).flatMap[Any, Throwable, S](g).fork)
    }

    final def transformWith[S](f: Try[T] => Future[S])(implicit ec: ExecutionContext): Future[S] = {
      val g: Try[T] => IO[Throwable, S] =
        (t: Try[T]) => IO.effect(f(t).join).flatten

      unsafeRun(ec, value.join.either.map(toTry(_)).flatMap[Any, Throwable, S](g).fork)
    }

    final def map[S](f: T => S)(implicit ec: ExecutionContext): Future[S] =
      unsafeRun(ec, value.join.map(f).fork)

    final def flatMap[S](f: T => Future[S])(implicit ec: ExecutionContext): Future[S] =
      unsafeRun(ec, value.join.map(f))

    final def flatten[S](implicit ev: T <:< Future[S]): Future[S] =
      flatMap(ev)(Global)

    final def filter(p: T => Boolean)(implicit ec: ExecutionContext): Future[T] =
      flatMap(
        t =>
          if (p(t)) Future.successful(t)
          else Future.failed(new NoSuchElementException)
      )

    final def withFilter(p: T => Boolean)(implicit ec: ExecutionContext): Future[T] =
      filter(p)

    final def collect[S](pf: PartialFunction[T, S])(implicit ec: ExecutionContext): Future[S] =
      unsafeRun(ec, value.join.flatMap[Any, Throwable, S](t => IO.effect(pf(t))).fork)

    final def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit ec: ExecutionContext): Future[U] =
      unsafeRun(ec, value.join.catchSome[Any, Throwable, U](pf.andThen(IO.succeed(_))).fork)

    final def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(
      implicit ec: ExecutionContext
    ): Future[U] =
      unsafeRun(ec, value.join.catchSome[Any, Throwable, U](pf.andThen(_.join)).fork)

    final def zip[U](that: Future[U]): Future[(T, U)] =
      value.zip(that)

    final def zipWith[U, R](that: Future[U])(f: (T, U) => R)(implicit ec: ExecutionContext): Future[R] =
      unsafeRun(ec, value.join.zipWith(that.join)(f).fork)

    final def fallbackTo[U >: T](that: Future[U]): Future[U] =
      value orElse that

    final def mapTo[S](implicit tag: ClassTag[S]): Future[S] = {
      implicit val ec = Global

      flatMap(t => Future(tag.runtimeClass.cast(t).asInstanceOf[S]))
    }

    final def andThen[U](pf: PartialFunction[Try[T], U])(implicit ec: ExecutionContext): Future[T] =
      unsafeRun(ec, value.join.either.flatMap { either =>
        IO.effect(pf lift (toTry(either))).either *> IO.succeed(either)
      }.absolve.fork)
  }
}
