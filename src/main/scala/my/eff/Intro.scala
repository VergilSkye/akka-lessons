package my.eff

import my.wrapper.Wrap

object Intro {

  def firstExample() {
    import cats._, data._
    import org.atnos.eff._

    type ReaderInt[A] = Reader[Int, A]
    type WriterString[A] = Writer[String, A]

    type Stack = Fx.fx3[WriterString, ReaderInt, Eval]

    import org.atnos.eff.all._
    import org.atnos.eff.syntax.all._

    // useful type aliases showing that the ReaderInt and the WriterString effects are "members" of R
    // note that R could have more effects
    type _readerInt[R] = ReaderInt |= R //  type |=[M[_], R] = MemberIn.|=[M, R] = MemberIn[M, R]
    type _writerString[R] = WriterString |= R //  type |=[M[_], R] = MemberIn.|=[M, R] = MemberIn[M, R]

    def program[R: _readerInt : _writerString : _eval]: Eff[R, Int] = for {
    /**
     * get the configuration(environment)
     * def ask[R, T](implicit member: Reader[T, ?] |= R): Eff[R, T]
     */
      n <- ask[R, Int]

      /**
       * write a given value
       * def tell[R, O](o: O)(implicit member: Writer[O, ?] |= R): Eff[R, Unit]
       */
      // log the current configuration value
      _ <- tell("the required power is " + n)

      // compute the nth power of 2
      a <- delay(math.pow(2, n.toDouble).toInt)

      // log the result
      _ <- tell("the result is " + a)
    } yield a

    // run the action with all the interpreters
    // each interpreter running one effect
    println(program[Stack].runReader(6).runWriter.runEval.run)
    //(64,List(the required power is 6, the result is 64))
  }

  def tutorial(): Unit ={
    // the type parameter A can be read as the type of value returned by the operation
    sealed trait KVStore[+A]

    case class Put[T](key: String, value: T) extends KVStore[Unit]
    case class Get[T](key: String) extends KVStore[Option[T]]
    case class Delete(key: String) extends KVStore[Unit]

    import org.atnos.eff._

    // T |= R is an alias for MemberIn[T, R]
    // stating that effects of type T[_] can be injected in the effect stack R
    // It is also equivalent to MemberIn[KVStore, R]
    type _kvstore[R] = KVStore |= R

    /** store returns nothing (i.e. Unit) */
    def store[T, R :_kvstore](key: String, value: T): Eff[R, Unit] =
      Eff.send[KVStore, R, Unit](Put(key, value))

    /** find returns a T value if the key exists */
    def find[T, R :_kvstore](key: String): Eff[R, Option[T]] =
      Eff.send[KVStore, R, Option[T]](Get(key))

    /** delete returns nothing (i.e. Unit) */
    def delete[T, R :_kvstore](key: String): Eff[R, Unit] =
      Eff.send(Delete(key))

    /** update composes get and put, and returns nothing. */
    def update[T, R :_kvstore](key: String, f: T => T): Eff[R, Unit] =
      for {
        ot <- find[T, R](key)
        _  <- ot.map(t => store[T, R](key, f(t))).getOrElse(Eff.pure(()))
      } yield ()

    /**
     * Build a program
     *
     * Now that we can construct values with KVStore effects
     * we can use our DSL to write “programs” using a for-comprehension:
     */
    def program[R :_kvstore]: Eff[R, Option[Int]] =
      for {
        _ <- store("wild-cats", 2)
        _ <- update[Int, R]("wild-cats", _ + 12)
        _ <- store("tame-cats", 5)
        n <- find[Int, R]("wild-cats")
        _ <- delete("tame-cats")
      } yield n

    /**
     * Write an interpreter for your program
     *
     * As you may have understood now, Eff is used to create an embedded DSL.
     * By itself, this DSL only represents a sequence of operations (defined by a recursive data structure);
     * it doesn’t produce anything.
     * Eff is a programming language inside your programming language!
     * So, like any other programming language, we need to interpret our abstract language into concrete values.
     * To do this, we will use an interpreter transforming our KVStore effects using a simple mutable map:
     */
    import org.atnos.eff._, interpret._
    import cats.Traverse
    import cats.implicits._
    import scala.collection.mutable._

    /**
     * Unsafe interpreter for KVStore effects
     *
     * the program will crash if a type is incorrectly specified.
     *
     * The interpreter requires the KVStore effect to be a Member of R (with <=)
     * Meaning that we can statically know the resulting type once we have removed
     * KVStore from R, and this type is m.Out.
     *
     * The interpreter uses the `interpretUnsafe` method from `org.atnos.eff.Interpreter` to implement a
     * stack-safe interpretation of effects as a side-effect.
     *
     * `interpretUnsafe` needs the definition of a side-effect where
     * we get each `KVStore[X]` effect, run side-effects and return a value `X`.
     *
     * The resulting effect stack is m.Out which is R without the KVStore effects
     *
     */
    def runKVStoreUnsafe[R, A](effects: Eff[R, A])(implicit m: KVStore <= R): Eff[m.Out, A] = {
      // a very simple (and imprecise) key-value store
      val kvs = Map.empty[String, Any]

      val sideEffect = new SideEffect[KVStore] {
        def apply[X](kv: KVStore[X]): X =
          kv match {
            case Put(key, value) =>
              println(s"put($key, $value)")
              kvs.put(key, value)
              ().asInstanceOf[X]

            case Get(key) =>
              println(s"get($key)")
              kvs.get(key).asInstanceOf[X]

            case Delete(key) =>
              println(s"delete($key)")
              kvs.remove(key)
              ().asInstanceOf[X]
          }

        def applicative[X, Tr[_] : Traverse](ms: Tr[KVStore[X]]): Tr[X] =
          ms.map(apply)
      }
      interpretUnsafe(effects)(sideEffect)(m)
    }

    /**
     * We can also interpret KVStore effects differently and
     * delegate the results to other effects in the same stack:
     */
    import org.atnos.eff._
    import org.atnos.eff.either._
    import org.atnos.eff.writer._
    import org.atnos.eff.state._
    import org.atnos.eff.interpret._
    import cats.implicits._
    import cats.data._

    type _writerString[R] = Writer[String, ?] |= R
    type _stateMap[R]     = State[Map[String, Any], ?] |= R

    /**
     * Safe interpreter for KVStore effects
     *
     * It uses the following effects:
     *
     *  - Writer to create log statements
     *  - State to update a key-value Map
     *  - Either to raise errors if the type of an object in the map is not of the expected type
     *
     *  The resulting effect stack is U which is R without the KVStore effects
     *
     *  Note that we just require the Throwable, Writer and State effects to
     *  be able to be created in the stack U
     *
     * This interpreter uses the org.atnos.eff.interpreter.translate method
     * translating one effect of the stack to other effects in the same stack
     *
     *
     * NOTE:
     * - It is really important for type inference that the effects for U are listed after those for R!
     *
     * Implicit member definitions will NOT be found with the following definition:
     *
     * def runKVStore[R, U :_throwableEither :_writerString :_stateMap, A](effects: Eff[R, A]) (
     *   implicit m: Member.Aux[KVStore, R, U]): Eff[U, A] = {
     *
     */
    def runKVStore[R, U, A](effects: Eff[R, A])
      (implicit m: Member.Aux[KVStore, R, U],
        throwable:_throwableEither[U],
        writer:_writerString[U],
        state:_stateMap[U]): Eff[U, A] = {

      translate(effects)(new Translate[KVStore, U] {
        def apply[X](kv: KVStore[X]): Eff[U, X] =
          kv match {
            case Put(key, value) =>
              for {
                _ <- tell(s"put($key, $value)")
                _ <- modify((map: Map[String, Any]) => map.updated(key, value))
                r <- fromEither(Either.catchNonFatal(().asInstanceOf[X]))
              } yield r

            case Get(key) =>
              for {
                _ <- tell(s"get($key)")
                m <- get[U, Map[String, Any]]
                r <- fromEither(Either.catchNonFatal(m.get(key).asInstanceOf[X]))
              } yield r

            case Delete(key) =>
              for {
                _ <- tell(s"delete($key)")
                u <- modify((map: Map[String, Any]) => map - key)
                r <- fromEither(Either.catchNonFatal(().asInstanceOf[X]))
              } yield r
          }
      })
    }

    /**
     * Run your program !!!
     */
    import org.atnos.eff._, syntax.all._

    // run the program with the unsafe interpreter
    println(runKVStoreUnsafe(program[Fx.fx1[KVStore]]).run)
    // put(wild-cats, 2)
    // get(wild-cats)
    // put(wild-cats, 14)
    // put(tame-cats, 5)
    // get(wild-cats)
    // delete(tame-cats)
    // Some(14)

    /**
     * With the safe interpreter, the process is the same and we need to
     * * specify an effect stack definition with all the effects
     * * call our “safe” interpreter
     * * call interpreters for all the other effects, including the final NoFx effect with run
     */
    import cats._, data._

    // run the program with the safe interpreter
    type Stack = Fx.fx4[KVStore, Throwable Either ?, State[Map[String, Any], ?], Writer[String, ?]]

    val (result2, logs2) =
      runKVStore(program[Stack]).runEither.evalState(Map.empty[String, Any]).runWriter.run

    println()
    println((result2.toString +: logs2).mkString("\n"))

    /**
     * Add some syntax
     */
    implicit class KVStoreOps[R, A](effects: Eff[R, A]) {
      def runStore[U](implicit m: Member.Aux[KVStore, R, U],
        throwable:_throwableEither[U],
        writer:_writerString[U],
        state:_stateMap[U]): Eff[U, A] =
        runKVStore(effects)
    }

    val (result3, logs3) =
      program[Stack].runStore.runEither.evalState(Map.empty[String, Any]).runWriter.run

    println()
    println((result3.toString +: logs3).mkString("\n"))
  }

  def typeBounds(): Unit ={
    trait MyTypeX[T]
    trait MyTypeZ[S]

    class MyTypeXZ[T,S] extends MyTypeX[T] with MyTypeZ[S]

    implicit val mytx: MyTypeX[Int] = new MyTypeX[Int]{}

    def myFunc[A : MyTypeX](a: A): String = a.getClass.toString
    //def myFunc$[A](a: A)(implicit $ev1: MyTypeX[A]) = a.getClass.toString

    println(myFunc(10))
  }

  def main(args: Array[String]): Unit = {
    Wrap("tutorial")(tutorial)
    Wrap("typeBounds")(typeBounds)
  }

}
