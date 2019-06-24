# Interop Future

[![CircleCI][ci-badge]][ci-url]

This library provides an interoperability layer with Scala's Future.

## From `Future`

This is the extension method added to `IO` companion object:

```scala
def fromFuture[A](ftr: () => Future[A])(ec: ExecutionContext): Task[A] =
```

There are a few things to clarify here:

- `ftr`, the expression producing the `Future` value, is a *thunk* (or `Function0`). The reason for that is, `Future` is eager, it means as soon as you call `Future.apply` the effect has started performing, that's not a desired behavior in Pure FP (which ZIO encourages). So it's recommended to declare expressions creating `Future`s using `def` instead of `val`.
- Also you have to be explicit on which EC you want to use, having it implicit, as in the standard library, is a bad practice.
- Finally, as you can see, the `IO` returned fixes the error type to `Throwable` since that's the only possible cause for a failed `Future`.

### Example

```scala
// EC is not implicit
val myEC: ExecutionContext = ...

// future defined in thunk using def
def myFuture: Future[ALotOfData] = myLegacyHeavyJobReturningFuture(...)
val myIO: Task[ALotOfData] = IO.fromFuture(myFuture _)(myEC)
```

## To `Future`

This extension method is added to values of type `Task[A]`:

```scala
def toFuture: UIO[Future[A]]
```

Notice that we don't actually return a `Future` but an infallible `IO` producing the `Future` when it's performed, that's again because as soon as we have a `Future` in our hands, whatever it does is already happening.

As an alternative, a more flexible extension method is added to any `IO[E, A]` to convert to `Future` as long as you can provide a function to convert from `E` to `Throwable`.

```scala
def toFutureE(f: E => Throwable): UIO[Future[A]]
```

### Example

```scala
val safeFuture: UIO[Future[MoarData]] = myShinyNewApiBasedOnZio(...).toFuture(MyError.toThrowable)
val itsHappening: Future[MoarData] = unsafeRun(safeFuture)
```

[ci-badge]: https://circleci.com/gh/zio/interop-future/tree/master.svg?style=svg
[ci-url]: https://circleci.com/gh/zio/interop-future/tree/master
