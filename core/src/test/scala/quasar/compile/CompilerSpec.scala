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

package quasar.compile

import slamdata.Predef._
import quasar.common.{CIName, JoinType, SortDir}
import quasar.common.data.Data
import quasar.frontend.logicalplan.{JoinCondition, JoinDir, LogicalPlan => LP}
import quasar.sql._
import quasar.std._, StdLib._, agg._, array._, date._, identity._, math._

import pathy.Path._
import matryoshka.data.Fix
import matryoshka.implicits._
import scalaz.{Divide => _, Select => _, _}, Scalaz._

class CompilerSpec extends quasar.Qspec with CompilerHelpers {
  // NB: imports are here to shadow duplicated names in [[quasar.sql]]. We
  //     need to do a better job of handling this.
  import quasar.std.StdLib._, relations._, StdLib.set._, string._, structural._
  import SemanticError._

  "compiler" should {
    "compile simple constant example 1" in {
      testLogicalPlanCompile(
        sqlE"select 1",
        lpf.constant(Data.Int(1)))
    }

    "compile simple constant example 1 with field name" in {
      testLogicalPlanCompile(
        sqlE"select 1 as one",
        makeObj("one" -> lpf.constant(Data.Int(1))))
    }

    "compile simple boolean literal (true)" in {
      testLogicalPlanCompile(
        sqlE"select true",
        lpf.constant(Data.Bool(true)))
    }

    "compile simple boolean literal (false)" in {
      testLogicalPlanCompile(
        sqlE"select false",
        lpf.constant(Data.Bool(false)))
    }

    "compile simple constant with multiple named projections" in {
      testLogicalPlanCompile(
        sqlE"""select 1.0 as a, "abc" as b""",
        makeObj(
          "a" -> lpf.constant(Data.Dec(1.0)),
          "b" -> lpf.constant(Data.Str("abc"))))
    }

    "compile expression with datetime, date, time, and interval" in {
      testFullLogicalPlanCompile(
        sqlE"""select timestamp("2014-11-17T22:00:00Z") + interval("PT43M40S"), date("2015-01-19"), time("14:21")""",
        lpf.invoke2(MapConcat,
          lpf.invoke2(MapConcat,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("0")),
              lpf.invoke2(Add,
                lpf.invoke1(OffsetDateTime, lpf.constant(Data.Str("2014-11-17T22:00:00Z"))),
                lpf.invoke1(Interval, lpf.constant(Data.Str("PT43M40S"))))),
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("1")),
              lpf.invoke1(LocalDate, lpf.constant(Data.Str("2015-01-19"))))),
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("2")),
            lpf.invoke1(LocalTime, lpf.constant(Data.Str("14:21"))))))
    }

    "compile simple constant from collection" in {
      testFullLogicalPlanCompile(sqlE"select 1 from zips",
        lpf.invoke1(Squash, lpf.constant(Data.Int(1))))
    }

    "compile query from Q#2755" in {
      val query = sqlE"""select substring("abcdefg", 0, trunc(pop / 10000)) from zips"""

      testFullLogicalPlanCompile(query,
        lpf.invoke1(Squash,
          lpf.invoke3(Substring,
            lpf.constant(Data.Str("abcdefg")),
            lpf.constant(Data.Int(0)),
            lpf.invoke1(Trunc,
              lpf.invoke2(Divide,
                lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("pop"))),
                lpf.constant(Data.Int(10000)))))))
    }

    "compile with typecheck in join condition" in {
      val query = sqlE"select * from zips join smallZips on zips.x = smallZips.foo.bar"

      testFullLogicalPlanCompile(query,
        lpf.let('__tmp0,
          lpf.join(
            read("zips"),
            read("smallZips"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10,
              lpf.invoke2(Eq,
                lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin9), lpf.constant(Data.Str("x"))),
                lpf.invoke2(MapProject,
                  lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin10), lpf.constant(Data.Str("foo"))),
                  lpf.constant(Data.Str("bar")))))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              JoinDir.Left.projectFrom(lpf.free('__tmp0)),
              JoinDir.Right.projectFrom(lpf.free('__tmp0))))))
    }

    "compile with typecheck in multiple join condition" in {
      val query =
        sqlE"""select l.sha as child,
               l.author.login as c_auth,
               r.sha as parent,
               r.author.login as p_auth
               from slamengine_commits as l join slamengine_commits_dup as r
               on r.sha = l.parents[0].sha and l.author.login = r.author.login"""

      testFullLogicalPlanCompile(query,
        lpf.let('__tmp0,
          lpf.join(
            read("slamengine_commits"),
            read("slamengine_commits_dup"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10,
              lpf.invoke2(And,
                lpf.invoke2(Eq,
                  lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin10), lpf.constant(Data.Str("sha"))),
                  lpf.invoke2(MapProject,
                    lpf.invoke2(ArrayProject,
                      lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin9), lpf.constant(Data.Str("parents"))),
                      lpf.constant(Data.Int(0))),
                    lpf.constant(Data.Str("sha")))),
                lpf.invoke2(Eq,
                  lpf.invoke2(MapProject,
                    lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin9), lpf.constant(Data.Str("author"))),
                    lpf.constant(Data.Str("login"))),
                  lpf.invoke2(MapProject,
                    lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin10), lpf.constant(Data.Str("author"))),
                    lpf.constant(Data.Str("login"))))))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              lpf.invoke2(MapConcat,
                lpf.invoke2(MapConcat,
                  lpf.invoke2(MakeMap,
                    lpf.constant(Data.Str("child")),
                    lpf.invoke2(MapProject,
                      JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                      lpf.constant(Data.Str("sha")))),
                  lpf.invoke2(MakeMap,
                    lpf.constant(Data.Str("c_auth")),
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject,
                        JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                        lpf.constant(Data.Str("author"))),
                      lpf.constant(Data.Str("login"))))),
                lpf.invoke2(MakeMap,
                  lpf.constant(Data.Str("parent")),
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("sha"))))),
              lpf.invoke2(MakeMap,
                lpf.constant(Data.Str("p_auth")),
                lpf.invoke2(MapProject,
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("author"))),
                  lpf.constant(Data.Str("login"))))))))
    }

    "compile with typecheck in multiple join condition followed by filter" in {
      val query =
        sqlE"""select l.sha as child,
               l.author.login as c_auth,
               r.sha as parent,
               r.author.login as p_auth
               from slamengine_commits as l join slamengine_commits_dup as r
               on r.sha = l.parents[0].sha and l.author.login = r.author.login
               where r.author.login || "," || l.author.login = "jdegoes,jdegoes" """

      testFullLogicalPlanCompile(query,
        lpf.let('__tmp0,
          lpf.let('__tmp1,
            lpf.join(
              read("slamengine_commits"),
              read("slamengine_commits_dup"),
              JoinType.Inner,
              JoinCondition('__leftJoin23, '__rightJoin24,
                lpf.invoke2(And,
                  lpf.invoke2(Eq,
                    lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin24), lpf.constant(Data.Str("sha"))),
                    lpf.invoke2(MapProject,
                      lpf.invoke2(ArrayProject,
                        lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin23), lpf.constant(Data.Str("parents"))),
                        lpf.constant(Data.Int(0))),
                      lpf.constant(Data.Str("sha")))),
                  lpf.invoke2(Eq,
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin23), lpf.constant(Data.Str("author"))),
                      lpf.constant(Data.Str("login"))),
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin24), lpf.constant(Data.Str("author"))),
                      lpf.constant(Data.Str("login"))))))),
            lpf.invoke2(Filter,
              lpf.free('__tmp1),
              lpf.invoke2(Eq,
                lpf.invoke2(ConcatOp,
                  lpf.invoke2(ConcatOp,
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject,
                        JoinDir.Right.projectFrom(lpf.free('__tmp1)),
                        lpf.constant(Data.Str("author"))),
                      lpf.constant(Data.Str("login"))),
                    lpf.constant(Data.Str(","))),
                  lpf.invoke2(MapProject,
                    lpf.invoke2(MapProject,
                      JoinDir.Left.projectFrom(lpf.free('__tmp1)),
                      lpf.constant(Data.Str("author"))),
                    lpf.constant(Data.Str("login")))),
                lpf.constant(Data.Str("jdegoes,jdegoes"))))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              lpf.invoke2(MapConcat,
                lpf.invoke2(MapConcat,
                  lpf.invoke2(MakeMap,
                    lpf.constant(Data.Str("child")),
                    lpf.invoke2(MapProject,
                      JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                      lpf.constant(Data.Str("sha")))),
                  lpf.invoke2(MakeMap,
                    lpf.constant(Data.Str("c_auth")),
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject,
                        JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                        lpf.constant(Data.Str("author"))),
                      lpf.constant(Data.Str("login"))))),
                lpf.invoke2(MakeMap,
                  lpf.constant(Data.Str("parent")),
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("sha"))))),
              lpf.invoke2(MakeMap,
                lpf.constant(Data.Str("p_auth")),
                lpf.invoke2(MapProject,
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("author"))),
                  lpf.constant(Data.Str("login"))))))))
    }

    "compile select substring" in {
      testLogicalPlanCompile(
        sqlE"select substring(bar, 2, 3) from foo",
        lpf.invoke1(Squash,
          lpf.invoke3(Substring,
            lpf.invoke2(MapProject, read("foo"), lpf.constant(Data.Str("bar"))),
            lpf.constant(Data.Int(2)),
            lpf.constant(Data.Int(3)))))
    }

    "compile select length" in {
      testLogicalPlanCompile(
        sqlE"select length(bar) from foo",
        lpf.invoke1(Squash,
          lpf.invoke1(Length, lpf.invoke2(MapProject, read("foo"), lpf.constant(Data.Str("bar"))))))
    }

    "compile simple select *" in {
      testLogicalPlanCompile(sqlE"select * from foo", lpf.invoke1(Squash, read("foo")))
    }

    "compile qualified select *" in {
      testLogicalPlanCompile(sqlE"select foo.* from foo", lpf.invoke1(Squash, read("foo")))
    }

    "compile qualified select * with additional fields" in {
      testLogicalPlanCompile(
        sqlE"select foo.*, bar.address from foo, bar",
        lpf.let('__tmp0,
          lpf.join(
            read("foo"),
            read("bar"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10, lpf.constant(Data.Bool(true)))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              JoinDir.Left.projectFrom(lpf.free('__tmp0)),
              makeObj(
                "address" ->
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("address"))))))))
    }

    "compile deeply-nested qualified select *" in {
      testLogicalPlanCompile(
        sqlE"select foo.bar.baz.*, bar.address from foo, bar",
        lpf.let('__tmp0,
          lpf.join(
            read("foo"),
            read("bar"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10, lpf.constant(Data.Bool(true)))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              lpf.invoke2(MapProject,
                lpf.invoke2(MapProject,
                  JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                  lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Str("baz"))),
              makeObj(
                "address" ->
                  lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                    lpf.constant(Data.Str("address"))))))))
    }

    "compile simple select with unnamed projection which is just an identifier" in {
      testLogicalPlanCompile(
        sqlE"select name, place from city",
        lpf.let('__tmp0, read("city"),
          lpf.invoke1(Squash,
            makeObj(
              "name"  -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("name"))),
              "place" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("place")))))))
    }

    "compile basic let" in {
      testLogicalPlanCompile(
        sqlE"foo := 5; foo",
        lpf.constant(Data.Int(5)))
    }

    "compile basic let, ignoring the form" in {
      testLogicalPlanCompile(
        sqlE"bar := 5; 7",
        lpf.constant(Data.Int(7)))
    }

    "compile nested lets" in {
      testLogicalPlanCompile(
        sqlE"foo := 5; bar := 7; bar + foo",
        lpf.invoke2(Add, lpf.constant(Data.Int(7)), lpf.constant(Data.Int(5))))
    }

    "compile let with select in body from let binding ident" in {
      val query = sqlE"foo := (1,2,3); select * from foo"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke1(ShiftArray,
            lpf.invoke2(ArrayConcat,
              lpf.invoke2(ArrayConcat,
                MakeArrayN[Fix[LP]](lpf.constant(Data.Int(1))).embed,
                MakeArrayN[Fix[LP]](lpf.constant(Data.Int(2))).embed),
              MakeArrayN[Fix[LP]](lpf.constant(Data.Int(3))).embed)))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with select in body selecting let binding ident" in {
      val query = sqlE"foo := 12; select foo from bar"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("foo")),
            lpf.constant(Data.Int(12))))

      testLogicalPlanCompile(query, expectation)
    }

    "fail to compile let inside select with ambigious reference" in {
      // TODO: Investigate why this is not producing an ambigious reference
      compile(sqlE"select foo from (bar := 12; baz) as quag") must_===
        compiledSubtableMissing("quag").wrapNel.left
    }

    "compile let inside select with table reference" in {
      val query = sqlE"select foo from (bar := 12; select * from baz) as quag"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("foo")),
            lpf.invoke2(MapProject,
              lpf.invoke1(Squash, read("baz")),
              lpf.constant(Data.Str("foo")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let inside select with ident reference" in {
      val query = sqlE"select foo from (bar := 12; select * from bar) as quag"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("foo")),
              lpf.invoke2(MapProject,
                lpf.invoke1(Squash, lpf.constant(Data.Int(12))),
                lpf.constant(Data.Str("foo")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile selection with same ident as nested let" in {
      val query = sqlE"select bar from (bar := 12; select * from bar) as quag"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("bar")),
            lpf.invoke2(MapProject,
              lpf.invoke1(Squash, lpf.constant(Data.Int(12))),
              lpf.constant(Data.Str("bar")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile selection with same ident as nested let and alias" in {
      val query = sqlE"select bar from (bar := 12; select * from bar) as bar"
      val expectation =
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("bar")),
            lpf.invoke1(Squash, lpf.constant(Data.Int(12)))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with select in form and body" in {
      val query = sqlE"foo := select * from bar; select * from foo"
      val expectation = lpf.invoke1(Squash, read("bar"))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with inner context that shares a table reference" in {
      val query = sqlE"select (foo := select * from bar; select * from foo) from foo"
      val expectation =
        lpf.invoke1(Squash, read("bar"))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with an inner context of as that shares a binding name" in {
      val query = sqlE"foo := 4; select * from bar as foo"
      val expectation = lpf.invoke1(Squash, read("bar"))

      testLogicalPlanCompile(query, expectation)
    }

    "fail to compile let with an inner context of let that shares a binding name in expression context" in {
      // TODO: Investigate why this is not producing an ambigious reference
      val query = sqlE"foo := 4; select * from (foo := bar; foo) as quag"

      compile(query) must_=== compiledSubtableMissing("quag").wrapNel.left
    }

    "compile let with an inner context of as that shares a binding name in table context" in {
      val query = sqlE"foo := 4; select * from (foo := select * from bar; foo) as quag"
      val expectation = lpf.invoke1(Squash, read("bar"))

      testLogicalPlanCompile(query, expectation)
    }

    "compile simple 1-table projection when root identifier is also a projection" in {
      // 'foo' must be interpreted as a projection because only this interpretation is possible
      testLogicalPlanCompile(
        sqlE"select foo.bar from baz",
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("bar")),
            lpf.invoke2(MapProject,
              lpf.invoke2(MapProject, read("baz"), lpf.constant(Data.Str("foo"))),
              lpf.constant(Data.Str("bar"))))))
    }

    "compile simple 1-table projection when root identifier is also a table ref" in {
      // 'foo' must be interpreted as a table reference because this
      // interpretation is possible and consistent with ANSI SQL.
      testLogicalPlanCompile(
        sqlE"select foo.bar from foo",
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("bar")),
            lpf.invoke2(MapProject, read("foo"), lpf.constant(Data.Str("bar"))))))
    }

    "compile two term addition from one table" in {
      testLogicalPlanCompile(
        sqlE"select foo + bar from baz",
        lpf.let('__tmp0, read("baz"),
          lpf.invoke1(Squash,
            lpf.invoke2(Add,
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar")))))))
    }

    "compile negate" in {
      testLogicalPlanCompile(
        sqlE"select -foo from bar",
        lpf.invoke1(Squash,
          lpf.invoke1(Negate, lpf.invoke2(MapProject, read("bar"), lpf.constant(Data.Str("foo"))))))
    }

    "compile modulo" in {
      testLogicalPlanCompile(
        sqlE"select foo % baz from bar",
        lpf.let('__tmp0, read("bar"),
          lpf.invoke1(Squash,
            lpf.invoke2(Modulo,
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("baz")))))))
    }

    "compile coalesce" in {
      testLogicalPlanCompile(
        sqlE"select coalesce(bar, baz) from foo",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
            lpf.invoke1(Squash,
              lpf.invoke3(Cond,
                lpf.invoke2(Eq, lpf.free('__tmp1), lpf.constant(Data.Null)),
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("baz"))),
                lpf.free('__tmp1))))))
    }

    "compile date field extraction" in {
      testLogicalPlanCompile(
        sqlE"""select date_part("day", baz) from foo""",
        lpf.invoke1(Squash,
          lpf.invoke1(ExtractDayOfMonth,
            lpf.invoke2(MapProject, read("foo"), lpf.constant(Data.Str("baz"))))))
    }

    "compile conditional" in {
      testLogicalPlanCompile(
        sqlE"select case when pop < 10000 then city else loc end from zips",
        lpf.let('__tmp0, read("zips"),
          lpf.invoke1(Squash,
            lpf.invoke3(Cond,
              lpf.invoke2(Lt,
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("pop"))),
                lpf.constant(Data.Int(10000))),
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("loc")))))))
    }

    "compile conditional (match) without else" in {
      testLogicalPlanCompile(
                   sqlE"""select case when pop = 0 then "nobody" end from zips""",
        compileExp(sqlE"""select case when pop = 0 then "nobody" else null end from zips"""))
    }

    "compile conditional (switch) without else" in {
      testLogicalPlanCompile(
                   sqlE"""select case pop when 0 then "nobody" end from zips""",
        compileExp(sqlE"""select case pop when 0 then "nobody" else null end from zips"""))
    }

    "have ~~ as alias for LIKE" in {
      testLogicalPlanCompile(
                   sqlE"""select pop from zips where city ~~ "%BOU%" """,
        compileExp(sqlE"""select pop from zips where city LIKE "%BOU%" """))
    }

    "have !~~ as alias for NOT LIKE" in {
      testLogicalPlanCompile(
                   sqlE"""select pop from zips where city !~~ "%BOU%" """,
        compileExp(sqlE"""select pop from zips where city NOT LIKE "%BOU%" """))
    }

    "compile array length" in {
      testLogicalPlanCompile(
        sqlE"select array_length(bar) from foo",
        lpf.invoke1(Squash,
          lpf.invoke1(ArrayLength,
            lpf.invoke2(MapProject, read("foo"), lpf.constant(Data.Str("bar"))))))
    }

    "compile concat" in {
      testLogicalPlanCompile(
        sqlE"""select concat(foo, concat(" ", bar)) from baz""",
        lpf.let('__tmp0, read("baz"),
          lpf.invoke1(Squash,
            lpf.invoke2(Concat,
              lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
              lpf.invoke2(Concat,
                lpf.constant(Data.Str(" ")),
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))))))))
    }

    "compile between" in {
      testLogicalPlanCompile(
        sqlE"select * from foo where bar between 1 and 10",
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(Filter,
              lpf.free('__tmp0),
              lpf.invoke3(Between,
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Int(1)),
                lpf.constant(Data.Int(10)))))))
    }

    "compile not between" in {
      testLogicalPlanCompile(
        sqlE"select * from foo where bar not between 1 and 10",
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(Filter,
              lpf.free('__tmp0),
              lpf.invoke1(Not,
                lpf.invoke3(Between,
                  lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                  lpf.constant(Data.Int(1)),
                  lpf.constant(Data.Int(10))))))))
    }

    "compile like" in {
      testLogicalPlanCompile(
        sqlE"""select bar from foo where bar like "a%" """,
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("bar")),
              lpf.invoke2(MapProject,
                lpf.invoke2(Filter,
                  lpf.free('__tmp0),
                  lpf.invoke3(Like,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                    lpf.constant(Data.Str("a%")),
                    lpf.constant(Data.Str("\\")))),
                lpf.constant(Data.Str("bar")))))))
    }

    "compile like with escape char" in {
      testLogicalPlanCompile(
        sqlE"""select bar from foo where bar like "a=%" escape "=" """,
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("bar")),
            lpf.invoke2(MapProject,
              lpf.invoke2(Filter,
                lpf.free('__tmp0),
                lpf.invoke3(Like,
                  lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                  lpf.constant(Data.Str("a=%")),
                  lpf.constant(Data.Str("=")))),
              lpf.constant(Data.Str("bar")))))))
    }

    "compile not like" in {
      testLogicalPlanCompile(
        sqlE"""select bar from foo where bar not like "a%" """,
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("bar")),
              lpf.invoke2(MapProject, lpf.invoke2(Filter,
                lpf.free('__tmp0),
                lpf.invoke1(Not,
                  lpf.invoke3(Like,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                    lpf.constant(Data.Str("a%")),
                    lpf.constant(Data.Str("\\"))))),
                lpf.constant(Data.Str("bar")))))))
    }

    "compile ~" in {
      testLogicalPlanCompile(
        sqlE"""select bar from foo where bar ~ "a.$$" """,
        lpf.let('__tmp0, read("foo"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("bar")),
              lpf.invoke2(MapProject,
                lpf.invoke2(Filter,
                  lpf.free('__tmp0),
                  lpf.invoke3(Search,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                    lpf.constant(Data.Str("a.$")),
                    lpf.constant(Data.Bool(false)))),
                lpf.constant(Data.Str("bar")))))))
    }

    "compile complex expression" in {
      testLogicalPlanCompile(
        sqlE"select avgTemp*9/5 + 32 from cities",
        lpf.invoke1(Squash,
          lpf.invoke2(Add,
            lpf.invoke2(Divide,
              lpf.invoke2(Multiply,
                lpf.invoke2(MapProject, read("cities"), lpf.constant(Data.Str("avgTemp"))),
                lpf.constant(Data.Int(9))),
              lpf.constant(Data.Int(5))),
            lpf.constant(Data.Int(32)))))
    }

    "compile parenthesized expression" in {
      testLogicalPlanCompile(
        sqlE"select (avgTemp + 32)/5 from cities",
        lpf.invoke1(Squash,
          lpf.invoke2(Divide,
            lpf.invoke2(Add,
              lpf.invoke2(MapProject, read("cities"), lpf.constant(Data.Str("avgTemp"))),
              lpf.constant(Data.Int(32))),
            lpf.constant(Data.Int(5)))))
    }

    "compile cross select *" in {
      testLogicalPlanCompile(
        sqlE"select * from person, car",
        lpf.let('__tmp0,
          lpf.join(
            read("person"),
            read("car"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10, lpf.constant(Data.Bool(true)))),
          lpf.invoke1(Squash,
            lpf.invoke2(MapConcat,
              JoinDir.Left.projectFrom(lpf.free('__tmp0)),
              JoinDir.Right.projectFrom(lpf.free('__tmp0))))))
    }

    "compile two term multiplication from two tables" in {
      testLogicalPlanCompile(
        sqlE"select person.age * car.modelYear from person, car",
        lpf.let('__tmp0,
          lpf.join(
            read("person"),
            read("car"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10, lpf.constant(Data.Bool(true)))),
          lpf.invoke1(Squash,
            lpf.invoke2(Multiply,
              lpf.invoke2(MapProject,
                JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                lpf.constant(Data.Str("age"))),
              lpf.invoke2(MapProject,
                JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                lpf.constant(Data.Str("modelYear")))))))
    }

    "compile simple where (with just a constant)" in {
      testLogicalPlanCompile(
        sqlE"select name from person where 1",
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("name")),
            lpf.invoke2(MapProject,
              lpf.invoke2(Filter, read("person"), lpf.constant(Data.Int(1))),
              lpf.constant(Data.Str("name"))))))
    }

    "compile simple where" in {
      testLogicalPlanCompile(
        sqlE"select name from person where age > 18",
        lpf.let('__tmp0, read("person"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("name")),
              lpf.invoke2(MapProject,
                lpf.invoke2(Filter,
                  lpf.free('__tmp0),
                  lpf.invoke2(Gt,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("age"))),
                    lpf.constant(Data.Int(18)))),
                lpf.constant(Data.Str("name")))))))
    }

    "compile simple group by" in {
      testLogicalPlanCompile(
        sqlE"select count(*) from person group by name",
        lpf.let('__tmp0, read("person"),
          lpf.invoke1(Squash,
            lpf.invoke1(Count,
              lpf.invoke2(GroupBy,
                lpf.free('__tmp0),
                MakeArrayN[Fix[LP]](lpf.invoke2(MapProject,
                  lpf.free('__tmp0),
                  lpf.constant(Data.Str("name")))).embed)))))
    }

    "compile group by with projected keys" in {
      testLogicalPlanCompile(
        sqlE"select lower(name), person.gender, avg(age) from person group by lower(person.name), gender",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('__tmp0),
              MakeArrayN[Fix[LP]](
                lpf.invoke1(Lower,
                  lpf.invoke2(MapProject,
                    lpf.free('__tmp0),
                    lpf.constant(Data.Str("name")))),
                lpf.invoke2(MapProject,
                  lpf.free('__tmp0),
                  lpf.constant(Data.Str("gender")))).embed),
            lpf.invoke1(Squash,
              makeObj(
                "0" ->
                  lpf.invoke1(Arbitrary,
                    lpf.invoke1(Lower,
                      lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("name"))))),
                "gender" ->
                  lpf.invoke1(Arbitrary,
                    lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("gender")))),
                "2" ->
                  lpf.invoke1(Avg,
                    lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("age")))))))))
    }

    "compile group by with perverse aggregated expression" in {
      testLogicalPlanCompile(
        sqlE"select count(name) from person group by name",
        lpf.let('__tmp0, read("person"),
          lpf.invoke1(Squash,
            lpf.invoke1(Count,
              lpf.invoke2(MapProject,
                lpf.invoke2(GroupBy,
                  lpf.free('__tmp0),
                  MakeArrayN[Fix[LP]](lpf.invoke2(MapProject,
                    lpf.free('__tmp0),
                    lpf.constant(Data.Str("name")))).embed),
                lpf.constant(Data.Str("name")))))))
    }

    "compile sum in expression" in {
      testLogicalPlanCompile(
        sqlE"select sum(pop) * 100 from zips",
        lpf.invoke1(Squash,
          lpf.invoke2(Multiply,
            lpf.invoke1(Sum, lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("pop")))),
            lpf.constant(Data.Int(100)))))
    }

    val setA =
      lpf.let('__tmp0, read("zips"),
        lpf.invoke1(Squash, makeObj(
          "loc" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("loc"))),
          "pop" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))))
    val setB =
      lpf.invoke1(Squash,
        lpf.invoke2(MakeMap,
          lpf.constant(Data.Str("city")),
          lpf.invoke2(MapProject,
            read("zips"),
            lpf.constant(Data.Str("city")))))

    "compile union" in {
      testLogicalPlanCompile(
        sqlE"select loc, pop from zips union select city from zips",
        lpf.normalizeLets(
          lpf.normalizeLets(
            lpf.let('__tmp1, lpf.invoke2(Union, setA, setB),
              lpf.invoke1(Arbitrary,
                lpf.invoke2(GroupBy, lpf.free('__tmp1), lpf.free('__tmp1)))))))
    }

    "compile union all" in {
      testLogicalPlanCompile(
        sqlE"select loc, pop from zips union all select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          lpf.invoke2(Union, setA, setB))))
    }

    "compile intersect" in {
      testLogicalPlanCompile(
        sqlE"select loc, pop from zips intersect select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          lpf.let('__tmp1, lpf.invoke2(Intersect, setA, setB),
            lpf.invoke1(Arbitrary,
              lpf.invoke2(GroupBy, lpf.free('__tmp1), lpf.free('__tmp1)))))))
    }

    "compile intersect all" in {
      testLogicalPlanCompile(
        sqlE"select loc, pop from zips intersect all select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          lpf.invoke2(Intersect, setA, setB))))
    }

    "compile except" in {
      testLogicalPlanCompile(
        sqlE"select loc, pop from zips except select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          lpf.invoke2(Except, setA, setB))))
    }

    "have {*} as alias for {:*}" in {
      testLogicalPlanCompile(
                   sqlE"SELECT bar{*} FROM foo",
        compileExp(sqlE"SELECT bar{:*} FROM foo"))
    }

    "have [*] as alias for [:*]" in {
      testLogicalPlanCompile(
                   sqlE"SELECT foo[*] FROM foo",
        compileExp(sqlE"SELECT foo[:*] FROM foo"))
    }

    "expand top-level map flatten" in {
      testLogicalPlanCompile(
                   sqlE"SELECT foo{:*} FROM foo",
        compileExp(sqlE"SELECT Flatten_Map(foo) FROM foo"))
    }

    "expand nested map flatten" in {
      testLogicalPlanCompile(
                   sqlE"SELECT foo.bar{:*} FROM foo",
        compileExp(sqlE"SELECT Flatten_Map(foo.bar) FROM foo"))
    }

    "expand field map flatten" in {
      testLogicalPlanCompile(
                   sqlE"SELECT bar{:*} FROM foo",
        compileExp(sqlE"SELECT Flatten_Map(foo.bar) FROM foo"))
    }

    "expand top-level array flatten" in {
      testLogicalPlanCompile(
                   sqlE"SELECT foo[:*] FROM foo",
        compileExp(sqlE"SELECT Flatten_Array(foo) FROM foo"))
    }

    "expand nested array flatten" in {
      testLogicalPlanCompile(
        sqlE"SELECT foo.bar[:*] FROM foo",
        compileExp(sqlE"SELECT Flatten_Array(foo.bar) FROM foo"))
    }

    "expand field array flatten" in {
      testLogicalPlanCompile(
                   sqlE"SELECT bar[:*] FROM foo",
        compileExp(sqlE"SELECT Flatten_Array(foo.bar) FROM foo"))
    }

    "compile top-level map flatten" in {
      testLogicalPlanCompile(
        sqlE"select zips{:*} from zips",
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("zips")),
            lpf.invoke1(FlattenMap, read("zips")))))
    }

    "have {_} as alias for {:_}" in {
      testLogicalPlanCompile(
                   sqlE"select length(commit.author{_}) from slamengine_commits",
        compileExp(sqlE"select length(commit.author{:_}) from slamengine_commits"))
    }

    "have [_] as alias for [:_]" in {
      testLogicalPlanCompile(
                   sqlE"select loc[_] / 10 from zips",
        compileExp(sqlE"select loc[:_] / 10 from zips"))
    }

    "compile map shift / unshift" in {
      val inner = lpf.invoke1(ShiftMap, lpf.invoke2(MapProject, lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("commit"))), lpf.constant(Data.Str("author"))))

      testLogicalPlanCompile(
        sqlE"select {commit.author{:_}: length(commit.author{:_}) ...} from slamengine_commits",
        lpf.let('__tmp0, read("slamengine_commits"),
          lpf.invoke1(Squash, lpf.invoke2(UnshiftMap, inner, lpf.invoke1(Length, inner)))))
    }

    "compile map shift / unshift keys" in {
      val inner = lpf.invoke1(ShiftMapKeys, lpf.invoke2(MapProject, lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("commit"))), lpf.constant(Data.Str("author"))))

      testLogicalPlanCompile(
        sqlE"select {commit.author{_:}: length(commit.author{_:})...} from slamengine_commits",
        lpf.let('__tmp0, read("slamengine_commits"),
          lpf.invoke1(Squash, lpf.invoke2(UnshiftMap, inner, lpf.invoke1(Length, inner)))))
    }

    "compile array shift / unshift" in {
      testLogicalPlanCompile(
        sqlE"select [loc[:_] / 10 ...] from zips",
        lpf.invoke1(Squash,
          lpf.invoke1(UnshiftArray,
            lpf.invoke2(Divide,
              lpf.invoke1(ShiftArray, lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("loc")))),
              lpf.constant(Data.Int(10))))))
    }

    "compile array shift / unshift indices" in {
      testLogicalPlanCompile(
        sqlE"select [loc[_:] * 10 ...] from zips",
        lpf.invoke1(Squash,
          lpf.invoke1(UnshiftArray,
            lpf.invoke2(Multiply,
              lpf.invoke1(ShiftArrayIndices, lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("loc")))),
              lpf.constant(Data.Int(10))))))
    }

    "compile array flatten" in {
      testLogicalPlanCompile(
        sqlE"select loc[:*] from zips",
        lpf.invoke1(Squash,
          lpf.invoke2(MakeMap,
            lpf.constant(Data.Str("loc")),
            lpf.invoke1(FlattenArray,
              lpf.invoke2(MapProject,
                read("zips"),
                lpf.constant(Data.Str("loc")))))))
    }

    "compile simple order by" in {
      testLogicalPlanCompile(
        sqlE"select name from person order by height",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "name" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("name"))),
                "__sd__0" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))))),
            lpf.invoke2(DeleteKey,
              lpf.sort(
                lpf.free('__tmp1),
                (lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))), SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by reusing selected field" in {
      testLogicalPlanCompile(
        sqlE"select name from person order by name",
        lpf.let('__tmp0,
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("name")),
              lpf.invoke2(
                MapProject,
                read("person"),
                lpf.constant(Data.Str("name"))))),
          lpf.sort(
            lpf.free('__tmp0),
            (lpf.invoke2(MapProject,
              lpf.free('__tmp0),
              lpf.constant(Data.Str("name"))), SortDir.asc).wrapNel)))
    }

    "compile order by reusing selected flattened field" in {
      testLogicalPlanCompile(
        sqlE"select quux[*] from foo order by quux[*]",
        lpf.let('__tmp0,
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("quux")),
              lpf.invoke1(
                FlattenArray,
                lpf.invoke2(MapProject,
                  read("foo"),
                  lpf.constant(Data.Str("quux")))))),
          lpf.sort(
            lpf.free('__tmp0),
            (lpf.invoke2(
              MapProject,
              lpf.free('__tmp0),
              lpf.constant(Data.Str("quux"))), SortDir.asc).wrapNel)))
    }

    "compile simple order by with filter" in {
      testLogicalPlanCompile(
        sqlE"""select name from person where gender = "male" order by name, height""",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            lpf.invoke2(Filter,
              lpf.free('__tmp0),
              lpf.invoke2(Eq,
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("gender"))),
                lpf.constant(Data.Str("male")))),
            lpf.let('__tmp2,
              lpf.invoke1(Squash,
                makeObj(
                  "name"    -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("name"))),
                  "__sd__0" -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("height"))))),
              lpf.invoke2(DeleteKey,
                lpf.sort(
                  lpf.free('__tmp2),
                  NonEmptyList(
                    (lpf.invoke2(MapProject, lpf.free('__tmp2), lpf.constant(Data.Str("name"))), SortDir.asc),
                    (lpf.invoke2(MapProject, lpf.free('__tmp2), lpf.constant(Data.Str("__sd__0"))), SortDir.asc))),
                lpf.constant(Data.Str("__sd__0")))))))
    }

    "compile simple order by with wildcard" in {
      testLogicalPlanCompile(
        sqlE"select * from person order by height",
        lpf.let('__tmp0, lpf.invoke1(Squash, read("person")),
          lpf.sort(
            lpf.free('__tmp0),
            (lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))), SortDir.asc).wrapNel)))
    }

    "compile simple order by with ascending and descending" in {
      testLogicalPlanCompile(
        sqlE"select * from person order by height desc, name",
        lpf.let('__tmp0, lpf.invoke1(Squash, read("person")),
          lpf.sort(
            lpf.free('__tmp0),
            NonEmptyList(
              (lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))), SortDir.desc),
              (lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("name"))), SortDir.asc)))))
    }

    "compile simple order by with expression" in {
      testLogicalPlanCompile(
        sqlE"select * from person order by height*2.54",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              lpf.invoke2(MapConcat,
                lpf.free('__tmp0),
                makeObj(
                  "__sd__0" -> lpf.invoke2(Multiply,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                    lpf.constant(Data.Dec(2.54)))))),
            lpf.invoke2(DeleteKey,
              lpf.sort(
                lpf.free('__tmp1),
                (lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))), SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with alias" in {
      testLogicalPlanCompile(
        sqlE"select firstName as name from person order by name",
        lpf.let('__tmp0,
          lpf.invoke1(Squash,
            makeObj(
              "name" -> lpf.invoke2(MapProject, read("person"), lpf.constant(Data.Str("firstName"))))),
          lpf.sort(
            lpf.free('__tmp0),
            (lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("name"))), SortDir.asc).wrapNel)))
    }

    "compile simple order by with expression in synthetic field" in {
      testLogicalPlanCompile(
        sqlE"select name from person order by height*2.54",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "name" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("name"))),
                "__sd__0" ->
                  lpf.invoke2(Multiply,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                    lpf.constant(Data.Dec(2.54))))),
            lpf.invoke2(DeleteKey,
              lpf.sort(
                lpf.free('__tmp1),
                (lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))), SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with nested projection" in {
      testLogicalPlanCompile(
        sqlE"select bar from foo order by foo.bar.baz.quux/3",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "bar" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                "__sd__0" -> lpf.invoke2(Divide,
                  lpf.invoke2(MapProject,
                    lpf.invoke2(MapProject,
                      lpf.invoke2(MapProject, lpf.free('__tmp0),
                        lpf.constant(Data.Str("bar"))),
                      lpf.constant(Data.Str("baz"))),
                    lpf.constant(Data.Str("quux"))),
                  lpf.constant(Data.Int(3))))),
            lpf.invoke2(DeleteKey,
              lpf.sort(
                lpf.free('__tmp1),
                (lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))), SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with root projection a table ref" in {
      // Note: not using wildcard here because the simple case is optimized
      //       differently
      testLogicalPlanCompile(
                   sqlE"select foo from bar order by bar.baz",
        compileExp(sqlE"select foo from bar order by baz"))
    }

    "compile order by with root projection a table ref with alias" in {
      // Note: not using wildcard here because the simple case is optimized
      //       differently
      testLogicalPlanCompile(
                   sqlE"select foo from bar as b order by b.baz",
        compileExp(sqlE"select foo from bar as b order by baz"))
    }

    "compile order by with root projection a table ref with alias, mismatched" in {
      testLogicalPlanCompile(
                   sqlE"select * from bar as b order by bar.baz",
        compileExp(sqlE"select * from bar as b order by b.bar.baz"))
    }

    "compile order by with root projection a table ref, embedded in expr" in {
      testLogicalPlanCompile(
                   sqlE"select * from bar order by bar.baz/10",
        compileExp(sqlE"select * from bar order by baz/10"))
    }

    "compile order by with root projection a table ref, embedded in complex expr" in {
      testLogicalPlanCompile(
                   sqlE"select * from bar order by bar.baz/10 - 3*bar.quux",
        compileExp(sqlE"select * from bar order by baz/10 - 3*quux"))
    }

    "compile multiple stages" in {
      // val sql =
      //   sqlE"""select height*2.54 as cm
      //          from person
      //          where height > 60
      //          group by gender, height
      //          having count(*) > 10
      //          order by cm
      //          offset 10
      //          limit 5"""

      // this is required while the test is pending because we require valid sql to be passed
      val sql = sqlE"select * from zips"

      testLogicalPlanCompile(sql,
        lpf.let('__tmp0, read("person"), // from person
          lpf.let('__tmp1,    // where height > 60
            lpf.invoke2(Filter,
              lpf.free('__tmp0),
              lpf.invoke2(Gt,
                lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                lpf.constant(Data.Int(60)))),
            lpf.let('__tmp2,    // group by gender, height
              lpf.invoke2(GroupBy,
                lpf.free('__tmp1),
                MakeArrayN[Fix[LP]](
                  lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("gender"))),
                  lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("height")))).embed),
              lpf.let('__tmp3,
                lpf.invoke1(Squash,     // select height*2.54 as cm
                  makeObj(
                    "cm" ->
                      lpf.invoke2(Multiply,
                        lpf.invoke1(Arbitrary,
                          lpf.invoke2(MapProject,
                            lpf.invoke2(Filter,   // having count(*) > 10
                              lpf.free('__tmp2),
                              lpf.invoke2(Gt, lpf.invoke1(Count, lpf.free('__tmp2)), lpf.constant(Data.Int(10)))),
                            lpf.constant(Data.Str("height")))),
                        lpf.constant(Data.Dec(2.54))))),
                lpf.invoke2(Take,
                  lpf.invoke2(Drop,
                    lpf.sort(  // order by cm
                      lpf.free('__tmp3),
                      (lpf.invoke2(MapProject, lpf.free('__tmp3), lpf.constant(Data.Str("cm"))), SortDir.asc).wrapNel),
                    lpf.constant(Data.Int(10))), // offset 10
                  lpf.constant(Data.Int(5))))))))    // limit 5
    }.pendingUntilFixed("reported in issue qz-3686")

    "compile simple sum" in {
      testLogicalPlanCompile(
        sqlE"select sum(height) from person",
        lpf.invoke1(Squash,
          lpf.invoke1(Sum, lpf.invoke2(MapProject, read("person"), lpf.constant(Data.Str("height"))))))
    }

    "compile simple inner equi-join" in {
      val query =
        sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id"

      testLogicalPlanCompile(query,
        lpf.let('__tmp0,
          lpf.join(
            read("foo"),
            read("bar"),
            JoinType.Inner,
            JoinCondition('__leftJoin9, '__rightJoin10,
              lpf.invoke2(Eq,
                lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin9), lpf.constant(Data.Str("id"))),
                lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin10), lpf.constant(Data.Str("foo_id")))))),
          lpf.invoke1(Squash,
                makeObj(
                  "name" ->
                    lpf.invoke2(MapProject,
                      JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                      lpf.constant(Data.Str("name"))),
                  "address" ->
                    lpf.invoke2(MapProject,
                      JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                      lpf.constant(Data.Str("address")))))))
    }

    "compile cross join to the equivalent inner equi-join" in {
      val query = sqlE"select foo.name, bar.address from foo, bar where foo.id = bar.foo_id"
      val equiv = sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id"

      val expected = renameJoinSides(compileExp(equiv))(
        '__leftJoin9, '__leftJoin23, '__rightJoin10, '__rightJoin24)

      testLogicalPlanCompile(query, expected)
    }

    "compile inner join with additional equi-condition to the equivalent inner equi-join" in {
      val query = sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id where foo.x = bar.y"
      val equiv = sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id and foo.x = bar.y"

      val expected = renameJoinSides(compileExp(equiv))(
        '__leftJoin9, '__leftJoin23, '__rightJoin10, '__rightJoin24)

      testLogicalPlanCompile(query, expected)
    }

    "compile inner non-equi join to the equivalent cross join" in {
      val query = sqlE"select foo.name, bar.address from foo join bar on foo.x < bar.y"
      val equiv = sqlE"select foo.name, bar.address from foo, bar where foo.x < bar.y"

      val expected = renameJoinSides(compileExp(equiv))(
        '__leftJoin23, '__leftJoin9, '__rightJoin24, '__rightJoin10)

      testLogicalPlanCompile(query, expected)
    }

    "compile nested cross join to the equivalent inner equi-join" in {
      val query = sqlE"select a.x, b.y, c.z from a, b, c where a.id = b.a_id and b.`_id` = c.b_id"
      val equiv = sqlE"select a.x, b.y, c.z from (a join b on a.id = b.a_id) join c on b.`_id` = c.b_id"

      testLogicalPlanCompile(query, compileExp(equiv))
    }.pendingUntilFixed("SD-1190 (should these really be identical as of #1943?)")

    "compile filtered cross join with one-sided conditions" in {
      val query =
        sqlE"select foo.name, bar.address from foo, bar where foo.id = bar.foo_id and foo.x < 10 and bar.y = 20"

      val equiv =
        sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id where foo.x < 10 and bar.y = 20"

      testLogicalPlanCompile(query, compileExp(equiv))
    }

    "compile filtered join with one-sided conditions" in {
      val query =
        sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id where foo.x < 10 and bar.y = 20"

      testLogicalPlanCompile(query,
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, read("bar"),
            lpf.let('__tmp2,
              lpf.join(
                lpf.invoke2(Filter,
                  lpf.free('__tmp0),
                  lpf.invoke2(Lt,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("x"))),
                    lpf.constant(Data.Int(10)))),
                lpf.invoke2(Filter,
                  lpf.free('__tmp1),
                  lpf.invoke2(Eq,
                    lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("y"))),
                    lpf.constant(Data.Int(20)))),
                JoinType.Inner,
                JoinCondition('__leftJoin23, '__rightJoin24,
                  lpf.invoke2(Eq,
                    lpf.invoke2(MapProject, lpf.joinSideName('__leftJoin23), lpf.constant(Data.Str("id"))),
                    lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin24), lpf.constant(Data.Str("foo_id")))))),
              lpf.invoke1(Squash,
                makeObj(
                  "name" -> lpf.invoke2(MapProject,
                    JoinDir.Left.projectFrom(lpf.free('__tmp2)),
                    lpf.constant(Data.Str("name"))),
                  "address" -> lpf.invoke2(MapProject,
                    JoinDir.Right.projectFrom(lpf.free('__tmp2)),
                    lpf.constant(Data.Str("address")))))))))
    }

    "compile simple left ineq-join" in {
      testLogicalPlanCompile(
        sqlE"select foo.name, bar.address from foo left join bar on foo.id < bar.foo_id",
        lpf.let('__tmp0,
          lpf.join(
            read("foo"),
            read("bar"),
            JoinType.LeftOuter,
            JoinCondition('left1, 'right2,
              lpf.invoke2(Lt,
                lpf.invoke2(MapProject, lpf.joinSideName('left1), lpf.constant(Data.Str("id"))),
                lpf.invoke2(MapProject, lpf.joinSideName('right2), lpf.constant(Data.Str("foo_id")))))),
          lpf.invoke1(Squash,
            makeObj(
              "name" ->
                lpf.invoke2(MapProject,
                  JoinDir.Left.projectFrom(lpf.free('__tmp0)),
                  lpf.constant(Data.Str("name"))),
              "address" ->
                lpf.invoke2(MapProject,
                  JoinDir.Right.projectFrom(lpf.free('__tmp0)),
                  lpf.constant(Data.Str("address")))))))
    }

    "compile complex equi-join" in {
      testLogicalPlanCompile(
        sqlE"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id join baz on baz.bar_id = bar.id",
        lpf.let('__tmp0,
          lpf.join(
            lpf.join(
              read("foo"),
              read("bar"),
              JoinType.Inner,
              JoinCondition('left3, 'right4,
                lpf.invoke2(Eq,
                  lpf.invoke2(MapProject,
                    lpf.joinSideName('left3),
                    lpf.constant(Data.Str("id"))),
                  lpf.invoke2(MapProject,
                    lpf.joinSideName('right4),
                    lpf.constant(Data.Str("foo_id")))))),
            read("baz"),
            JoinType.Inner,
            JoinCondition('__leftJoin23, '__rightJoin24,
              lpf.invoke2(Eq,
                lpf.invoke2(MapProject, lpf.joinSideName('__rightJoin24),
                  lpf.constant(Data.Str("bar_id"))),
                lpf.invoke2(MapProject,
                  JoinDir.Right.projectFrom(lpf.joinSideName('__leftJoin23)),
                  lpf.constant(Data.Str("id")))))),
          lpf.invoke1(Squash,
            makeObj(
              "name" ->
                lpf.invoke2(MapProject,
                  JoinDir.Left.projectFrom(JoinDir.Left.projectFrom(lpf.free('__tmp0))),
                  lpf.constant(Data.Str("name"))),
              "address" ->
                lpf.invoke2(MapProject,
                  JoinDir.Right.projectFrom(JoinDir.Left.projectFrom(lpf.free('__tmp0))),
                  lpf.constant(Data.Str("address")))))))
    }

    "compile sub-select in filter" in {
      testLogicalPlanCompile(
        sqlE"select city, pop from zips where pop > (select avg(pop) from zips)",
        read("zips"))
    }.pendingUntilFixed

    "compile simple sub-select" in {
      testLogicalPlanCompile(
        sqlE"select temp.name, temp.size from (select zips.city as name, zips.pop as size from zips) as temp",
        lpf.let('__tmp0, read("zips"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "name" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "size" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            lpf.invoke1(Squash,
              makeObj(
                "name" -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("name"))),
                "size" -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("size"))))))))
    }

    "compile sub-select with same un-qualified names" in {
      testLogicalPlanCompile(
        sqlE"select city, pop from (select city, pop from zips) as temp",
        lpf.let('__tmp0, read("zips"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "city" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "pop" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            lpf.invoke1(Squash,
              makeObj(
                "city" -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("city"))),
                "pop" -> lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("pop"))))))))
    }

    "compile simple distinct" in {
      testLogicalPlanCompile(
        sqlE"select distinct city from zips",
        lpf.let(
          '__tmp0,
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("city")),
              lpf.invoke2(
                MapProject,
                read("zips"),
                lpf.constant(Data.Str("city"))))),
          lpf.invoke1(Arbitrary,
            lpf.invoke2(GroupBy, lpf.free('__tmp0), lpf.free('__tmp0)))))
    }

    "compile simple distinct ordered" in {
      testLogicalPlanCompile(
        sqlE"select distinct city from zips order by city",
        lpf.let(
          '__tmp0,
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("city")),
              lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("city"))))),
          lpf.let(
            '__tmp1,
            lpf.sort(
              lpf.free('__tmp0),
              (lpf.invoke2(
                MapProject,
                lpf.free('__tmp0),
                lpf.constant(Data.Str("city"))), SortDir.asc).wrapNel),
            lpf.sort(
              lpf.invoke1(Arbitrary,
                lpf.invoke2(GroupBy, lpf.free('__tmp1), lpf.free('__tmp1))),
              (lpf.invoke2(
                MapProject,
                lpf.invoke1(Arbitrary,
                  lpf.invoke2(GroupBy, lpf.free('__tmp1), lpf.free('__tmp1))),
                lpf.constant(Data.Str("city"))), SortDir.asc).wrapNel))))
    }

    "compile distinct with unrelated order by" in {
      testLogicalPlanCompile(
        sqlE"select distinct city from zips order by pop desc",
        lpf.let('__tmp0,
          read("zips"),
          lpf.let('__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "city" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "__sd__0" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            lpf.let('__tmp2,
              lpf.sort(
                lpf.free('__tmp1),
                (lpf.invoke2(MapProject, lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))), SortDir.desc).wrapNel),
              lpf.invoke2(DeleteKey,
                lpf.sort(
                  lpf.invoke1(First,
                    lpf.invoke2(GroupBy,
                      lpf.free('__tmp2),
                      lpf.invoke2(DeleteKey,
                        lpf.free('__tmp2),
                        lpf.constant(Data.Str("__sd__0"))))),
                  (lpf.invoke2(MapProject, lpf.invoke1(First,
                    lpf.invoke2(GroupBy,
                      lpf.free('__tmp2),
                      lpf.invoke2(DeleteKey,
                        lpf.free('__tmp2),
                        lpf.constant(Data.Str("__sd__0"))))), lpf.constant(Data.Str("__sd__0"))), SortDir.desc).wrapNel),
                lpf.constant(Data.Str("__sd__0")))))))
    }

    "compile count(distinct(...))" in {
      testLogicalPlanCompile(
        sqlE"select count(distinct(lower(city))) from zips",
        lpf.let(
          '__tmp0,
          lpf.invoke1(Lower,
            lpf.invoke2(MapProject, read("zips"), lpf.constant(Data.Str("city")))),
          lpf.invoke1(Squash,
            lpf.invoke1(Count,
              lpf.invoke1(Arbitrary,
                lpf.invoke2(GroupBy, lpf.free('__tmp0), lpf.free('__tmp0)))))))
    }

    "compile simple distinct with two named projections" in {
      testLogicalPlanCompile(
        sqlE"select distinct city as CTY, state as ST from zips",
        lpf.let('__tmp0, read("zips"),
          lpf.let(
            '__tmp1,
            lpf.invoke1(Squash,
              makeObj(
                "CTY" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "ST" -> lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("state"))))),
            lpf.invoke1(Arbitrary,
              lpf.invoke2(GroupBy, lpf.free('__tmp1), lpf.free('__tmp1))))))
    }

    "compile count distinct with two exprs" in {
      testLogicalPlanCompile(
        sqlE"select count(distinct city, state) from zips",
        read("zips"))
    }.pendingUntilFixed

    "compile distinct as function" in {
      testLogicalPlanCompile(
        sqlE"select distinct(city, state) from zips",
        read("zips"))
    }.pendingUntilFixed

    "fail with ambiguous reference" in {
      compile(sqlE"select foo from bar, baz") must be_-\/(
        NonEmptyList(
          SemanticError.AmbiguousReference(
            ident[Fix[Sql]]("foo").embed,
            List(
              TableRelationAST(currentDir </> file("baz"),None),
              TableRelationAST(currentDir </> file("bar"),None)))))
    }

    "fail with ambiguous reference in cond" in {
      compile(sqlE"""select (case when a = 1 then "ok" else "reject" end) from bar, baz""") must be_-\/
    }

    "fail with ambiguous reference in else" in {
      compile(sqlE"""select (case when bar.a = 1 then "ok" else foo end) from bar, baz""") must be_-\/
    }

    "fail with duplicate alias" in {
      compile(sqlE"select car.name as name, owner.name as name from owners as owner join cars as car on car.`_id` = owner.carId") must_===
        SemanticError.duplicateAlias("name").wrapNel.left
    }

    "translate free variable" in {
      testLogicalPlanCompile(sqlE"select name from zips where age < :age",
        lpf.let('__tmp0, read("zips"),
          lpf.invoke1(Squash,
            lpf.invoke2(MakeMap,
              lpf.constant(Data.Str("name")),
              lpf.invoke2(MapProject,
                lpf.invoke2(Filter,
                  lpf.free('__tmp0),
                  lpf.invoke2(Lt,
                    lpf.invoke2(MapProject, lpf.free('__tmp0), lpf.constant(Data.Str("age"))),
                    lpf.free('age))),
                lpf.constant(Data.Str("name")))))))
    }
  }

  "error when too few arguments passed to a function" in {
    fullCompile(sqlE"""select substring("foo") from zips""") must_===
      wrongArgumentCount(CIName("substring"), 3, 1).wrapNel.left
  }

  "error when too many arguments passed to a function" in {
    fullCompile(sqlE"select count(*, 1, 2, 4) from zips") must_===
      wrongArgumentCount(CIName("count"), 1, 4).wrapNel.left
  }

  "reduceGroupKeys" should {
    import Compiler.reduceGroupKeys

    "insert ARBITRARY" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('tmp0),
              MakeArrayN[Fix[LP]](lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city")))).embed),
            lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("city")))))
      val exp =
        lpf.let('tmp0, read("zips"),
          lpf.invoke1(Arbitrary,
            lpf.invoke2(MapProject,
              lpf.invoke2(GroupBy,
                lpf.free('tmp0),
                MakeArrayN[Fix[LP]](lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city")))).embed), lpf.constant(Data.Str("city")))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }

    "insert ARBITRARY with intervening filter" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('tmp0),
              MakeArrayN[Fix[LP]](lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city")))).embed),
            lpf.let('tmp2,
              lpf.invoke2(Filter, lpf.free('tmp1), lpf.invoke2(Gt, lpf.invoke1(Count, lpf.free('tmp1)), lpf.constant(Data.Int(10)))),
              lpf.invoke2(MapProject, lpf.free('tmp2), lpf.constant(Data.Str("city"))))))
      val exp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('tmp0),
              MakeArrayN[Fix[LP]](lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city")))).embed),
            lpf.invoke1(Arbitrary,
              lpf.invoke2(MapProject,
                lpf.invoke2(Filter,
                  lpf.free('tmp1),
                  lpf.invoke2(Gt, lpf.invoke1(Count, lpf.free('tmp1)), lpf.constant(Data.Int(10)))),
                lpf.constant(Data.Str("city"))))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }.pendingUntilFixed("reported in issue qz-3686")

    "not insert redundant Reduction" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          lpf.invoke1(Count,
            lpf.invoke2(MapProject,
              lpf.invoke2(GroupBy,
                lpf.free('tmp0),
                MakeArrayN[Fix[LP]](lpf.invoke2(MapProject, lpf.free('tmp0),
                  lpf.constant(Data.Str("city")))).embed), lpf.constant(Data.Str("city")))))

      reduceGroupKeys(lp) must equalToPlan(lp)
    }

    "insert ARBITRARY with multiple keys and mixed projections" in {
      val lp =
        lpf.let('tmp0,
          read("zips"),
          lpf.let('tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('tmp0),
              MakeArrayN[Fix[LP]](
                lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city"))),
                lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("state")))).embed),
            makeObj(
              "city" -> lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("city"))),
              "1"    -> lpf.invoke1(Count, lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("state")))),
              "loc"  -> lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("loc"))),
              "2"    -> lpf.invoke1(Sum, lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("pop")))))))
      val exp =
        lpf.let('tmp0,
          read("zips"),
          lpf.let('tmp1,
            lpf.invoke2(GroupBy,
              lpf.free('tmp0),
              MakeArrayN[Fix[LP]](
                lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("city"))),
                lpf.invoke2(MapProject, lpf.free('tmp0), lpf.constant(Data.Str("state")))).embed),
            makeObj(
              "city" -> lpf.invoke1(Arbitrary, lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("city")))),
              "1"    -> lpf.invoke1(Count, lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("state")))),
              "loc"  -> lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("loc"))),
              "2"    -> lpf.invoke1(Sum, lpf.invoke2(MapProject, lpf.free('tmp1), lpf.constant(Data.Str("pop")))))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }
  }

  "inferred names" should {
    def inferred(s: String): ProjectionName =
      Inferred(s).right

    def namesOf(projections: List[Proj[Fix[Sql]]]): List[ProjectionName] =
      projectionNames(projections, None).toOption.toList.join.firsts

    "create unique names" >> {
      "when two fields have the same name" in {
        val query = sqlE"SELECT owner.name, car.name from owners as owner join cars as car on car.`_id` = owner.carId"
        val projections = query.project.asInstanceOf[Select[Fix[Sql]]].projections

        namesOf(projections) must_=== List(inferred("name"), inferred("name0"))
      }

      "when multiple flattened fields have the same name" in {
        val query = sqlE"SELECT foo{:*}, foo{*:}, foo[:*], foo[*:] from bar"
        val projections = query.project.asInstanceOf[Select[Fix[Sql]]].projections

        namesOf(projections) must_=== List(inferred("foo"), inferred("foo0"), inferred("foo1"), inferred("foo2"))
      }

      "when a field and an alias have the same name" in {
        val query = sqlE"SELECT owner.name, car.model as name from owners as owner join cars as car on car.`_id` = owner.carId"
        val projections = query.project.asInstanceOf[Select[Fix[Sql]]].projections

        namesOf(projections) must_=== List(inferred("name0"), inferred("name"))
      }
    }
  }
}
