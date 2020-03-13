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

package quasar.common.data

import slamdata.Predef._
import quasar.Qspec

import qdata._
import scalaz.syntax.traverse._
import scalaz.std.list._
import scalaz.std.option._

object QDataDataSpec extends Qspec with DataGenerators {

  val qdataRoundtrip = new QDataRoundtrip[Data]

  // Data.Int and Data.Dec type as QLong and QDouble resp. when possible, else default to QReal
  // we don't need to adjust for this case because Equal[Data] compares them as equal
  def adjustExpected(data: Data): Option[Data] = data match {
    case Data.Obj(obj) =>
      val traversed: Option[List[(String, Data)]] =
        obj.toList.traverse {
          case (k, v) => adjustExpected(v).map((k, _))
        }
      traversed.map(o => Data.Obj(ListMap(o: _*)))

    case Data.Arr(arr) =>
      arr.traverse(v => adjustExpected(v)).map(Data.Arr(_))

    case Data.NA => None // not supported by qdata

    case d => Some(d)
  }

  "roundtrip arbitrary Data" >> prop { data: Data =>
    qdataRoundtrip.roundtrip(data) must_=== adjustExpected(data)
  }.set(minTestsOk = 1000)
}
