/*
 * Copyright 2020 Precog Data
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

package quasar.contrib.scalaz

import slamdata.Predef._

import cats.effect.IO
import scalaz._, Scalaz._

trait CatchableInstances {
  implicit val catsIOCatchable: Catchable[IO] =
    new Catchable[IO] {
      def attempt[A](ioa: IO[A]): IO[Throwable \/ A] =
        ioa.attempt.map(_.disjunction)

      def fail[A](err: Throwable): IO[A] =
        IO.raiseError(err)
    }
}

object catchable extends CatchableInstances
