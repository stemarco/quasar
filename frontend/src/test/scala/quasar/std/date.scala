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

package quasar.std

import slamdata.Predef._
import quasar.common.data.Data
import qdata.time.DateTimeInterval

import scalaz._

class DateSpecs extends quasar.Qspec {
  import DateLib._

  "parseInterval" should {
    def fromMillis(millis: Long) = \/-(Data.Interval(DateTimeInterval.ofMillis(millis)))

    def hms(hours: Int, minutes: Int, seconds: Int, millis: Int) =
      fromMillis((((hours.toLong*60) + minutes)*60 + seconds)*1000 + millis)

    "parse millis" in {
      parseInterval("PT0.001S") must_=== fromMillis(1)
    }

    "parse negative parts" in {
      parseInterval("PT-1H-1M-1S") must_=== hms(-1, -1, -1, 0)
    }

    "parse fractional parts" in {
      // The spec says "the smallest value may have a decimal fraction"
      parseInterval("PT1.5H") must_=== hms(1, 30, 0, 0)
      parseInterval("PT5H1.5M") must_=== hms(5, 1, 30, 0)
    }.pendingUntilFixed("SD-720")

    "parse days" in {
      parseInterval("P1D") must_=== \/-(Data.Interval(DateTimeInterval.ofDays(1)))
    }

    "parse ymd" in {
      parseInterval("P1Y1M1D") must_=== \/-(Data.Interval(DateTimeInterval.make(1, 1, 1, 0, 0)))
    }
  }
}
