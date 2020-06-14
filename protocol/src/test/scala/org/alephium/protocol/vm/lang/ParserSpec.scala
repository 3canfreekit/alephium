package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.crypto.ED25519
import org.alephium.protocol.{ALF, vm}
import org.alephium.protocol.vm.{StatelessContext, StatelessScript, StatelessVM, Val}
import org.alephium.serde._
import org.alephium.util.{AVector, AlephiumSpec, U64}

class ParserSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.tpe(_)).get.value is Val.U64
    fastparse.parse("x: U64", Parser.argument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Val.U64, isMutable = false)
    fastparse.parse("mut x: U64", Parser.argument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Val.U64, isMutable = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.callId(_)).get.value is Ast.CallId("add", false)
    fastparse.parse("add!", Lexer.callId(_)).get.value is Ast.CallId("add", true)
  }

  it should "parse exprs" in {
    fastparse.parse("x + y", Parser.expr(_)).get.value is
      Binop(Add, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("(x + y)", Parser.expr(_)).get.value is
      ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
    fastparse.parse("(x + y) + (x + y)", Parser.expr(_)).get.value is
      Binop(Add,
            ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))),
            ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
    fastparse.parse("x + y * z + u", Parser.expr(_)).get.value is
      Binop(
        Add,
        Binop(Add, Variable(Ident("x")), Binop(Mul, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u")))
    fastparse.parse("foo(x + y) + bar(x + y)", Parser.expr(_)).get.value is
      Binop(
        Add,
        Call(CallId("foo", false), List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))),
        Call(CallId("bar", false), List(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
      )
  }

  it should "parse return" in {
    fastparse.parse("return x, y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return x + y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return (x + y)", Parser.ret(_)).isSuccess is true
  }

  it should "parse statements" in {
    fastparse.parse("let x = 1", Parser.statement(_)).isSuccess is true
    fastparse.parse("x = 1", Parser.statement(_)).isSuccess is true
    fastparse.parse("x = true", Parser.statement(_)).isSuccess is true
    fastparse.parse("add(x, y)", Parser.statement(_)).isSuccess is true
  }

  it should "parse functions" in {
    fastparse
      .parse("fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }", Parser.func(_))
      .isSuccess is true
  }

  it should "parse contracts" in {
    val contract =
      s"""
         |// comment
         |contract Foo(mut x: U64, mut y: U64, c: U64) {
         |  // comment
         |  fn add0(a: U64, b: U64) -> (U64) {
         |    return (a + b)
         |  }
         |
         |  fn add1() -> (U64) {
         |    return (x + y)
         |  }
         |
         |  fn add2(d: U64) -> () {
         |    let mut z = 0u
         |    z = d
         |    x = x + z // comment
         |    y = y + z // comment
         |    return
         |  }
         |}
         |""".stripMargin
    fastparse.parse(contract, Parser.contract(_)).isSuccess is true

    val ast = fastparse.parse(contract, Parser.contract(_)).get.value
    ast.check()
  }

  it should "infer types" in {
    def check(xMut: String,
              a: String,
              aType: String,
              b: String,
              bType: String,
              rType: String,
              fname: String,
              validity: Boolean = false) = {
      val contract =
        s"""
         |contract Foo($xMut x: U64) {
         |  fn add($a: $aType, $b: $bType) -> ($rType) {
         |    x = a + b
         |    return (a - b)
         |  }
         |
         |  fn $fname() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
      val ast = fastparse.parse(contract, Parser.contract(_)).get.value
      if (validity) ast.check() else assertThrows[Checker.Error](ast.check())
    }

    check("mut", "a", "U64", "b", "U64", "U64", "foo", true)
    check("", "a", "U64", "b", "U64", "U64", "foo")
    check("mut", "x", "U64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "x", "U64", "U64", "foo")
    check("mut", "a", "I64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "b", "I64", "U64", "foo")
    check("mut", "a", "U64", "b", "U64", "I64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64, U64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64", "add")
  }

  it should "generate IR code" in {
    val input =
      s"""
         |contract Foo(x: U64) {
         |
         |  fn add(a: U64) -> (U64) {
         |    return square(x) + square(a)
         |  }
         |
         |  fn square(n: U64) -> (U64) {
         |    return n * n
         |  }
         |}
         |""".stripMargin
    val ast      = fastparse.parse(input, Parser.contract(_)).get.value
    val ctx      = ast.check()
    val contract = ast.toIR(ctx)

    deserialize[StatelessScript](serialize(contract)) isE contract
    StatelessVM.execute(StatelessContext.test,
                        contract,
                        AVector(Val.U64(U64.One)),
                        AVector(Val.U64(U64.Two))) isE
      Seq(Val.U64(U64.unsafe(5)))
  }

  it should "verify signature" in {
    val input =
      s"""
         |contract P2PKH(hash: Byte32) {
         |  fn verify(pk: Byte32) -> () {
         |    checkEq!(hash, keccak256!(pk))
         |    checkSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin
    val ast      = fastparse.parse(input, Parser.contract(_)).get.value
    val ctx      = ast.check()
    val contract = ast.toIR(ctx)

    deserialize[StatelessScript](serialize(contract)) isE contract

    val (priKey, pubKey) = ED25519.generatePriPub()
    val pubKeyHash       = ALF.Hash.hash(pubKey.bytes)
    val signature        = ED25519.sign(ALF.Hash.zero.bytes, priKey)
    StatelessVM.execute(
      StatelessContext(ALF.Hash.zero, vm.Stack.unsafe(AVector(signature), 1)),
      contract,
      AVector(Val.Byte32(pubKeyHash.toByte32)),
      AVector(Val.Byte32(pubKey.toByte32))
    ) isE Seq.empty[Val]
  }

  it should "test the following typical examples" in {
    def test(input: String, args: AVector[Val]): Assertion = {
      val ast      = fastparse.parse(input, Parser.contract(_)).get.value
      val ctx      = ast.check()
      val contract = ast.toIR(ctx)

      deserialize[StatelessScript](serialize(contract)) isE contract
      StatelessVM.execute(StatelessContext.test, contract, AVector.empty, args) isE Seq.empty[Val]
    }

    test(
      s"""
         |contract Main() {
         |
         |  fn main() -> () {
         |    let an_i64 = 5i // Suffix annotation
         |    let an_u64 = 5u
         |    let an_i256 = 5I
         |    let an_u256 = 5U
         |
         |    // Or a default will be used.
         |    let default_integer = 7   // `U64`
         |
         |    // A mutable variable's value can be changed.
         |    let mut another_i64 = 5i
         |    let mut another_u64 = 5u
         |    let mut another_i256 = 5I
         |    let mut another_u256 = 5U
         |    another_i64 = 6i
         |    another_u64 = 6u
         |    another_i256 = 6I
         |    another_u256 = 6U
         |
         |    let mut bool = true
         |    bool = false
         |
         |    return
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )
  }
}
