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
import quasar.contrib.iota.{:<<:, ACopK}
import quasar.ejson
import quasar.ejson.{
  Common,
  EJson,
  Extension,
  SizedType,
  Type => EType,
  TypeTag,
  ExtEJson,
  CommonEJson
}
import quasar.fp._
import qdata.time.{DateTimeInterval, OffsetDate => QOffsetDate}

import java.time.{
  LocalDate => JLocalDate,
  LocalDateTime => JLocalDateTime,
  LocalTime => JLocalTime,
  OffsetDateTime => JOffsetDateTime,
  OffsetTime => JOffsetTime,
  ZoneOffset
}
import scala.Any

import matryoshka._
import matryoshka.patterns._
import monocle.{Iso, Optional, Prism}
import qdata.{QDataDecode, QDataEncode}
import scalaz.{Lens => _, Optional=>_, _}, Scalaz._

sealed abstract class Data extends Product with Serializable

object Data {
  final case object Null extends Data

  final case class Str(value: String) extends Data

  val _str = Prism.partial[Data, String] { case Data.Str(s) => s } (Data.Str(_))

  final case class Bool(value: Boolean) extends Data
  val True = Bool(true)
  val False = Bool(false)

  val _bool =
    Prism.partial[Data, Boolean] { case Data.Bool(b) => b } (Data.Bool(_))

  sealed abstract class Number extends Data {
    override def equals(other: Any) = (this, other) match {
      case (Int(v1), Number(v2)) => BigDecimal(v1) ≟ v2
      case (Dec(v1), Number(v2)) => v1 ≟ v2
      case _                     => false
    }
  }
  object Number {
    def unapply(value: Data): Option[BigDecimal] = value match {
      case Int(value) => Some(BigDecimal(value))
      case Dec(value) => Some(value)
      case _ => None
    }
  }
  final case class Dec(value: BigDecimal) extends Number

  val _dec =
    Prism.partial[Data, BigDecimal] { case Data.Dec(i) => i } (Data.Dec(_))

  final case class Int(value: BigInt) extends Number

  val _int = Prism.partial[Data, BigInt] { case Data.Int(i) => i } (Data.Int(_))

  object Obj {
    @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
    def apply(xs: (String -> Data)*): Obj = new Obj(ListMap(xs: _*))
  }

  final case class Obj(value: ListMap[String, Data]) extends Data

  val _obj =
    Prism.partial[Data, ListMap[String, Data]] { case Data.Obj(m) => m } (Data.Obj(_))

  def singletonObj(k: String, v: Data): Data =
    Obj(ListMap(k -> v))

  final case class Arr(value: List[Data]) extends Data

  val _arr =
    Prism.partial[Data, List[Data]] { case Data.Arr(l) => l } (Data.Arr(_))

  final case class OffsetDateTime(value: JOffsetDateTime) extends Data

  val _offsetDateTime =
    Prism.partial[Data, JOffsetDateTime] { case Data.OffsetDateTime(ts) => ts } (Data.OffsetDateTime)

  final case class OffsetTime(value: JOffsetTime) extends Data

  val _offsetTime =
    Prism.partial[Data, JOffsetTime] { case Data.OffsetTime(ts) => ts } (Data.OffsetTime)

  final case class OffsetDate(value: QOffsetDate) extends Data

  val _offsetDate =
    Prism.partial[Data, QOffsetDate] { case Data.OffsetDate(ts) => ts } (Data.OffsetDate)

  final case class LocalDateTime(value: JLocalDateTime) extends Data

  val _localDateTimeOptional = Optional[Data, JLocalDateTime] { case LocalDateTime(k) => Some(k); case _ => None }(ldt => _ => LocalDateTime(ldt))
  val _localDateTime =
    Prism.partial[Data, JLocalDateTime] { case Data.LocalDateTime(ts) => ts } (Data.LocalDateTime)

  final case class LocalTime(value: JLocalTime) extends Data

  val _localTimeIso = Iso[LocalTime, JLocalTime](_.value)(LocalTime(_))
  val _localTime =
    Prism.partial[Data, JLocalTime] { case Data.LocalTime(t) => t } (Data.LocalTime)

  final case class LocalDate(value: JLocalDate) extends Data

  val _localDate =
    Prism.partial[Data, JLocalDate] { case Data.LocalDate(d) => d } (Data.LocalDate)

  final case class Interval(value: DateTimeInterval) extends Data

  val _interval =
    Prism.partial[Data, DateTimeInterval]
      { case Data.Interval(dti) => dti }(Data.Interval)

  /**
   An object to represent any value that might come from a backend, but that
   we either don't know about or can't represent in this ADT. We represent it
   with JS's `undefined`, just because no other value will ever be translated
   that way.
   */
  final case object NA extends Data

  object DateTimeLike {
    def unapply(data: Data): Option[Data] = data match {
      case d @ Data.OffsetDateTime(_) => d.some
      case d @ Data.OffsetDate(_) => d.some
      case d @ Data.OffsetTime(_) => d.some
      case d @ Data.LocalDateTime(_) => d.some
      case d @ Data.LocalDate(_) => d.some
      case d @ Data.LocalTime(_) => d.some
      case d @ Data.Interval(_) => d.some
      case _ => none
    }
  }

  object OffsetLike {
    def unapply(data: Data): Option[Data] = data match {
      case d @ Data.OffsetDateTime(_) => d.some
      case d @ Data.OffsetDate(_) => d.some
      case d @ Data.OffsetTime(_) => d.some
      case _ => none
    }
  }

  object LocalLike {
    def unapply(data: Data): Option[Data] = data match {
      case d @ Data.LocalDateTime(_) => d.some
      case d @ Data.LocalDate(_) => d.some
      case d @ Data.LocalTime(_) => d.some
      case _ => none
    }
  }

  implicit val dataShow: Show[Data] = Show.showFromToString

  implicit val dataEqual: Equal[Data] = Equal.equalA

  implicit val dataQDataEncode: QDataEncode[Data] = QDataData
  implicit val dataQDataDecode: QDataDecode[Data] = QDataData

  implicit val dataCorecursive: Corecursive.Aux[Data, EJson] =
    new Corecursive[Data] {
      type Base[T] = EJson[T]
      def embed(t: EJson[Data])(implicit BF: Functor[Base]) = fromEJson(t)
    }

  object DateTimeConstants {
    val year: String = "year"
    val month: String = "month"
    val day: String = "day" // day of month
    val hour: String = "hour"
    val minute: String = "minute"
    val second: String = "second"
    val nanosecond: String = "nanosecond"
    val offset: String = "offset"
  }

  object EJsonType {
    def apply(tag: TypeTag): Data =
      Data.Obj(ListMap(EType.TypeKey -> Data.Str(tag.value)))

    def unapply(data: Data) = data match {
      case Data.Obj(map) =>
        map.get(EType.TypeKey) >>= {
          case Data.Str(str) => TypeTag(str).some
          case _             => None
        }
      case _ => None
    }
  }

  object EJsonTypeSize {
    def apply(tag: TypeTag, size: BigInt): Data =
      Obj(ListMap(
        EType.TypeKey     -> Data.Str(tag.value),
        SizedType.SizeKey -> Data.Int(size)))

    def unapply(data: Data) = data match {
      case Data.Obj(map) =>
        ((map.get(EType.TypeKey) ⊛ map.get(SizedType.SizeKey)) {
          case (Data.Str(str), Data.Int(size)) => (TypeTag(str), size).some
          case _                               => None
        }).join
      case _ => None
    }
  }

  val fromCommon: Algebra[Common, Data] = {
    case ejson.Arr(value)  => Arr(value)
    case ejson.Null()      => Null
    case ejson.Bool(value) => Bool(value)
    case ejson.Str(value)  => Str(value)
    case ejson.Dec(value)  => Dec(value)
  }

  def extract[A, B](fa: Option[Data], p: Prism[Data, A])(f: A => B): Option[B] =
    fa.flatMap(p.getOption).map(f)

  val nanosPerSec = 1000000000L

  val fromExtension: Algebra[Extension, Data] = {
    case ejson.Meta(value, meta) => (meta, value) match {

      case (EJsonType(TypeTag.OffsetDateTime), Obj(map)) =>
        (extract(map.get(DateTimeConstants.year), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.month), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.day), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.hour), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.minute), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.second), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.nanosecond), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.offset), _int)(_.toInt)) {
            (y, mo, d, h, mi, s, n, o) =>
              OffsetDateTime(JOffsetDateTime.of(y, mo, d, h, mi, s, n,
                ZoneOffset.ofTotalSeconds(o)))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.OffsetTime), Obj(map)) =>
        (extract(map.get(DateTimeConstants.hour), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.minute), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.second), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.nanosecond), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.offset), _int)(_.toInt)) {
            (h, m, s, n, o) =>
              OffsetTime(JOffsetTime.of(h, m, s, n, ZoneOffset.ofTotalSeconds(o)))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.OffsetDate), Obj(map)) =>
        (extract(map.get(DateTimeConstants.year), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.month), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.day), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.offset), _int)(_.toInt)) {
            (y, m, d, o) =>
              OffsetDate(QOffsetDate(JLocalDate.of(y, m, d), ZoneOffset.ofTotalSeconds(o)))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.LocalDateTime), Obj(map)) =>
        (extract(map.get(DateTimeConstants.year), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.month), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.day), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.hour), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.minute), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.second), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.nanosecond), _int)(_.toInt)) {
            (y, mo, d, h, mi, s, n) =>
              LocalDateTime(JLocalDateTime.of(y, mo, d, h, mi, s, n))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.LocalTime), Obj(map)) =>
        (extract(map.get(DateTimeConstants.hour), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.minute), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.second), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.nanosecond), _int)(_.toInt)) {
            (h, m, s, n) =>
              LocalTime(JLocalTime.of(h, m, s, n))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.LocalDate), Obj(map)) =>
        (extract(map.get(DateTimeConstants.year), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.month), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.day), _int)(_.toInt)) {
            (y, m, d) =>
              LocalDate(JLocalDate.of(y, m, d))
          }.getOrElse(NA)

      case (EJsonType(TypeTag.Interval), Obj(map)) =>
        (extract(map.get(DateTimeConstants.year), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.month), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.day), _int)(_.toInt) ⊛
          extract(map.get(DateTimeConstants.second), _int)(_.toLong) ⊛
          extract(map.get(DateTimeConstants.nanosecond), _int)(_.toLong)) {
            (y, m, d, s, n) =>
              Interval(DateTimeInterval.make(y, m, d, s, n))
          }.getOrElse(NA)

      case (_, _) => value
    }

    case ejson.Map(value) =>
      value.traverse(Bitraverse[(?, ?)].leftTraverse.traverse(_) {
        case Str(key) => key.some
        case _        => None
      }).fold[Data](NA)(pairs => Obj(ListMap(pairs: _*)))

    case ejson.Int(value) => Int(value)

    case ejson.Char(value) => Str(value.toString)
  }

  // TODO: Data should be replaced with EJson. These just exist to bridge the
  //       gap in the meantime.
  val fromEJson: Algebra[EJson, Data] = {
    case ExtEJson(ext) => fromExtension(ext)
    case CommonEJson(com) => fromCommon(com)
  }

  /** Converts the parts of `Data` that it can, then stores the rest in,
    * effectively, `Free.Pure`.
    */
  def toEJson[F[a] <: ACopK[a]](implicit C: Common :<<: F, E: Extension :<<: F):
      Coalgebra[CoEnv[Data, F, ?], Data] =
    ed => CoEnv(ed match {
      case Arr(value) => C.inj(ejson.Arr(value)).right
      case Obj(value) =>
        E.inj(ejson.Map(value.toList.map(_.leftMap(Str(_))))).right
      case Null => C.inj(ejson.Null()).right
      case Bool(value) => C.inj(ejson.Bool(value)).right
      case Str(value) => C.inj(ejson.Str(value)).right
      case Dec(value) => C.inj(ejson.Dec(value)).right
      case Int(value) => E.inj(ejson.Int(value)).right

      case OffsetDateTime(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.year       -> Int(value.getYear),
          DateTimeConstants.month      -> Int(value.getMonth.getValue),
          DateTimeConstants.day        -> Int(value.getDayOfMonth),
          DateTimeConstants.hour       -> Int(value.getHour),
          DateTimeConstants.minute     -> Int(value.getMinute),
          DateTimeConstants.second     -> Int(value.getSecond),
          DateTimeConstants.nanosecond -> Int(value.getNano),
          DateTimeConstants.offset     -> Int(value.getOffset.getTotalSeconds))),
        EJsonType(TypeTag.OffsetDateTime))).right

      case OffsetTime(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.hour       -> Int(value.getHour),
          DateTimeConstants.minute     -> Int(value.getMinute),
          DateTimeConstants.second     -> Int(value.getSecond),
          DateTimeConstants.nanosecond -> Int(value.getNano),
          DateTimeConstants.offset     -> Int(value.getOffset.getTotalSeconds))),
        EJsonType(TypeTag.OffsetTime))).right

      case OffsetDate(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.year   -> Int(value.date.getYear),
          DateTimeConstants.month  -> Int(value.date.getMonth.getValue),
          DateTimeConstants.day    -> Int(value.date.getDayOfMonth),
          DateTimeConstants.offset -> Int(value.offset.getTotalSeconds))),
        EJsonType(TypeTag.OffsetDate))).right

      case LocalDateTime(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.year       -> Int(value.getYear),
          DateTimeConstants.month      -> Int(value.getMonth.getValue),
          DateTimeConstants.day        -> Int(value.getDayOfMonth),
          DateTimeConstants.hour       -> Int(value.getHour),
          DateTimeConstants.minute     -> Int(value.getMinute),
          DateTimeConstants.second     -> Int(value.getSecond),
          DateTimeConstants.nanosecond -> Int(value.getNano))),
        EJsonType(TypeTag.LocalDateTime))).right

      case LocalTime(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.hour       -> Int(value.getHour),
          DateTimeConstants.minute     -> Int(value.getMinute),
          DateTimeConstants.second     -> Int(value.getSecond),
          DateTimeConstants.nanosecond -> Int(value.getNano))),
        EJsonType(TypeTag.LocalTime))).right

      case LocalDate(value) => E.inj(ejson.Meta(
        Obj(ListMap(
          DateTimeConstants.year  -> Int(value.getYear),
          DateTimeConstants.month -> Int(value.getMonth.getValue),
          DateTimeConstants.day   -> Int(value.getDayOfMonth))),
        EJsonType(TypeTag.LocalDate))).right

      case Interval(value) =>
        E.inj(ejson.Meta(
          Obj(ListMap(
            DateTimeConstants.year       -> Int(value.period.getYears),
            DateTimeConstants.month      -> Int(value.period.getMonths),
            DateTimeConstants.day        -> Int(value.period.getDays),
            DateTimeConstants.second     -> Int(value.duration.getSeconds),
            DateTimeConstants.nanosecond -> Int(value.duration.getNano))),
          EJsonType(TypeTag.Interval))).right

      case data => data.left
    })
}
