/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.pkg

import scala.{ inline, AnyVal }
import scalaz.{ Show, Order, Equal }
import scala.util.Try
import java.lang.Throwable

final class EqualBy[A] {
  def apply[B](f: A => B)(implicit z: Equal[B]): Equal[A] = z contramap f
}
final class OrdBy[A] {
  def apply[B](f: A => B)(implicit z: Order[B]): Order[A] = z contramap f
}
final class ShowBy[A] {
  def apply[B](f: A => B)(implicit z: Show[B]): Show[A] = Show.show[A](x => z show f(x))
}
final class QuasarExtensionOps[A](private val self: A) extends AnyVal {
  @inline def |>[B](f: A => B): B = f(self)
  @inline def ->[B](y: B): (A, B) = scala.Tuple2(self, y)
}
final class QuasarTryOps[A](private val self: Try[A]) extends AnyVal {
  def |(expr: => A): A = fold(_ => expr, x => x)
  def fold[B](f: Throwable => B, g: A => B): B = self match {
    case scala.util.Success(x) => g(x)
    case scala.util.Failure(t) => f(t)
  }
}


/****

object boop {
  val ArrowAssoc = null
  implicit def quasarExtensionOps[A](x: A): quasar.pkg.QuasarExtensionOps[A] = new quasar.pkg.QuasarExtensionOps(x)
  val simpleData = List(
    Data.Obj(ListMap("a" -> Data.Int(1))),
    Data.Obj(ListMap("b" -> Data.Int(2))),
    Data.Obj(ListMap("c" -> Data.Arr(List(Data.Int(3))))))

  val simpleExpected = List("a,b,c[0]", "1,,", ",2,", ",,3").mkString("", "\r\n", "\r\n")
}

****/
