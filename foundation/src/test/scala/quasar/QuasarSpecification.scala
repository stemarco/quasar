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

package quasar

import java.lang.String
import scala._

import org.specs2.main.ArgProperty
import org.specs2.execute._
import scalaz._

/** Use Qspec if you can, QuasarSpecification only if you must.
 *  An abstract class allows the many trait forwarders to be reused
 *  by all the subclasses. Mixing in the trait means that your
 *  specification sprouts hundreds of pure forwarders.
 */
abstract class Qspec extends QuasarSpecification

trait QuasarSpecification extends AnyRef
        with org.specs2.mutable.SpecLike
        with org.specs2.specification.core.SpecificationStructure
        with org.specs2.matcher.ShouldExpectations
        with org.specs2.matcher.MatchResultCombinators
        with org.specs2.matcher.ValueChecks
        with org.specs2.matcher.MatcherZipOperators
        with org.specs2.matcher.DisjunctionMatchers
        with org.specs2.matcher.ValidationMatchers
        with org.specs2.execute.PendingUntilFixed
        with org.specs2.ScalaCheck
        with quasar.contrib.specs2.ScalazEqualityMatchers
        with ScalazSpecs2Instances
{
  outer =>

  // Fail fast and report all timings when running on CI.
  if (scala.sys.env contains "TRAVIS") {
    args.report(showtimes = ArgProperty(true))
  }

  implicit class Specs2ScalazOps[A : Equal : Show](lhs: A) {
    def must_=(rhs: A) = lhs must equal(rhs)
    def must_!=(rhs: A) = lhs must not(equal(rhs))
  }

  /** Allows marking non-deterministically failing tests as such,
   *  in the manner of pendingUntilFixed but such that it will not
   *  fail regardless of whether it seems to pass or fail.
   */
  implicit class FlakyTest[T : AsResult](t: => T) {
    import org.specs2.execute._
    def flakyTest: Result = flakyTest("")
    def flakyTest(m: String): Result = ResultExecution.execute(AsResult(t)) match {
      case s: Success => s
      case r          =>
        val explain = if (m == "") "" else s" ($m)"
        Skipped(s"${r.message}, but test is marked as flaky$explain", r.expected)
    }
  }

  implicit class QuasarOpsForAsResultable[T: AsResult](t: => T) {
    /** Steps in front of the standard specs2 implicit. */
    def pendingUntilFixed: Result = pendingUntilFixed("")

    def pendingUntilFixed(m: String): Result =
      outer.toPendingUntilFixed(t).pendingUntilFixed(m)
  }
}
