/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

//todo: allow infix type patterns
//todo verify when stableId's should be just plain qualified type ids

package scala.tools.nsc
package ast.parser

import scala.collection.mutable
import mutable.ListBuffer
import scala.reflect.internal.{Precedence, ModifierFlags => Flags}
import scala.reflect.internal.util.{ SourceFile, Position, FreshNameCreator, ListOfNil }
import Tokens._
import scala.annotation.tailrec

/** Historical note: JavaParsers started life as a direct copy of Parsers
 *  but at a time when that Parsers had been replaced by a different one.
 *  Later it was dropped and the original Parsers reinstated, leaving us with
 *  massive duplication between Parsers and JavaParsers.
 *
 *  This trait and the similar one for Scanners/JavaScanners represents
 *  the beginnings of a campaign against this latest incursion by Cutty
 *  McPastington and his army of very similar soldiers.
 */
trait ParsersCommon extends ScannersCommon { self =>
  val global : Global
  // the use of currentUnit in the parser should be avoided as it might
  // cause unexpected behaviour when you work with two units at the
  // same time; use Parser.unit instead
  import global.{currentUnit => _, _}

  def newLiteral(const: Any) = Literal(Constant(const))
  def literalUnit            = gen.mkSyntheticUnit()

  /** This is now an abstract class, only to work around the optimizer:
   *  methods in traits are never inlined.
   */
  abstract class ParserCommon {
    val in: ScannerCommon
    def deprecationWarning(off: Offset, msg: String, since: String): Unit
    def accept(token: Token): Int

    /** Methods inParensOrError and similar take a second argument which, should
     *  the next token not be the expected opener (e.g. LPAREN) will be returned
     *  instead of the contents of the groupers.  However in all cases accept(LPAREN)
     *  will be called, so a parse error will still result.  If the grouping is
     *  optional, in.token should be tested before calling these methods.
     */
    @inline final def inParens[T](body: => T): T = {
      accept(LPAREN)
      val ret = body
      accept(RPAREN)
      ret
    }
    @inline final def inParensOrError[T](body: => T, alt: T): T =
      if (in.token == LPAREN) inParens(body)
      else { accept(LPAREN) ; alt }

    @inline final def inParensOrUnit[T](body: => Tree): Tree = inParensOrError(body, literalUnit)
    @inline final def inParensOrNil[T](body: => List[T]): List[T] = inParensOrError(body, Nil)

    @inline final def inBraces[T](body: => T): T = {
      accept(LBRACE)
      val ret = body
      accept(RBRACE)
      ret
    }
    @inline final def inBracesOrError[T](body: => T, alt: T): T =
      if (in.token == LBRACE) inBraces(body)
      else { accept(LBRACE) ; alt }

    @inline final def inBracesOrNil[T](body: => List[T]): List[T] = inBracesOrError(body, Nil)
    @inline final def inBracesOrUnit[T](body: => Tree): Tree = inBracesOrError(body, literalUnit)
    @inline final def dropAnyBraces[T](body: => T): T =
      if (in.token == LBRACE) inBraces(body)
      else body

    @inline final def inBrackets[T](body: => T): T = {
      accept(LBRACKET)
      val ret = body
      accept(RBRACKET)
      ret
    }

    /** Creates an actual Parens node (only used during parsing.)
     */
    @inline final def makeParens(body: => List[Tree]): Parens =
      Parens(inParens(if (in.token == RPAREN) Nil else body))
  }
}

/** Performs the following context-free rewritings:
 *
 *  <ol>
 *    <li>
 *      Places all pattern variables in Bind nodes. In a pattern, for
 *      identifiers `x`:<pre>
 *                 x  => x @ _
 *               x:T  => x @ (_ : T)</pre>
 *    </li>
 *    <li>Removes pattern definitions (PatDef's) as follows:
 *      If pattern is a simple (typed) identifier:<pre>
 *        <b>val</b> x = e     ==>  <b>val</b> x = e
 *        <b>val</b> x: T = e  ==>  <b>val</b> x: T = e</pre>
 *
 *      if there are no variables in pattern<pre>
 *        <b>val</b> p = e  ==>  e match (case p => ())</pre>
 *
 *      if there is exactly one variable in pattern<pre>
 *        <b>val</b> x_1 = e <b>match</b> (case p => (x_1))</pre>
 *
 *      if there is more than one variable in pattern<pre>
 *        <b>val</b> p = e  ==>  <b>private synthetic val</b> t$ = e <b>match</b> (case p => (x_1, ..., x_N))
 *                        <b>val</b> x_1 = t$._1
 *                        ...
 *                        <b>val</b> x_N = t$._N</pre>
 *    </li>
 *    <li>
 *       Removes function types as follows:<pre>
 *        (argtpes) => restpe   ==>   scala.Function_n[argtpes, restpe]</pre>
 *    </li>
 *    <li>
 *      Wraps naked case definitions in a match as follows:<pre>
 *        { cases }   ==>   (x => x.match {cases})<span style="font-family:normal;">, except when already argument to match</span></pre>
 *    </li>
 *  </ol>
 */
trait Parsers extends Scanners with MarkupParsers with ParsersCommon {
self =>
  val global: Global
  import global._

  case class OpInfo(lhs: Tree, operator: TermName, targs: List[Tree], offset: Offset) {
    def precedence = Precedence(operator.toString)
  }

  class SourceFileParser(val source: SourceFile) extends Parser {

    /** The parse starting point depends on whether the source file is self-contained:
     *  if not, the AST will be supplemented.
     */
    def parseStartRule =
      if (source.isSelfContained) () => compilationUnit()
      else () => scriptBody()

    def newScanner(): Scanner = new SourceFileScanner(source)

    val in = newScanner()
    in.init()

    def unit = global.currentUnit

    // suppress warnings; silent abort on errors
    def warning(offset: Offset, msg: String): Unit = ()
    def deprecationWarning(offset: Offset, msg: String, since: String): Unit = ()

    def syntaxError(offset: Offset, msg: String): Unit = throw new MalformedInput(offset, msg)
    def incompleteInputError(msg: String): Unit = throw new MalformedInput(source.content.length - 1, msg)

    object symbXMLBuilder extends SymbolicXMLBuilder(this, preserveWS = true) { // DEBUG choices
      val global: self.global.type = self.global
    }

    /** the markup parser
     * The first time this lazy val is accessed, we assume we were trying to parse an xml literal.
     * The current position is recorded for later error reporting if it turns out
     * that we don't have the xml library on the compilation classpath.
     */
    private[this] lazy val xmlp = {
      unit.encounteredXml(o2p(in.offset))
      new MarkupParser(this, preserveWS = true)
    }

    def xmlLiteral() : Tree = xmlp.xLiteral
    def xmlLiteralPattern() : Tree = xmlp.xLiteralPattern
  }

  class OutlineParser(source: SourceFile) extends SourceFileParser(source) {

    def skipBraces[T](body: T): T = {
      accept(LBRACE)
      var openBraces = 1
      while (in.token != EOF && openBraces > 0) {
        if (in.token == XMLSTART) xmlLiteral()
        else {
          if (in.token == LBRACE) openBraces += 1
          else if (in.token == RBRACE) openBraces -= 1
          in.nextToken()
        }
      }
      body
    }

    override def blockExpr(): Tree = skipBraces(EmptyTree)

    override def templateBody(isPre: Boolean) = skipBraces((noSelfType, EmptyTree.asList))
  }

  class UnitParser(override val unit: global.CompilationUnit, patches: List[BracePatch]) extends SourceFileParser(unit.source) { uself =>
    def this(unit: global.CompilationUnit) = this(unit, Nil)

    override def newScanner() = new UnitScanner(unit, patches)

    override def warning(offset: Offset, msg: String): Unit =
      reporter.warning(o2p(offset), msg)

    override def deprecationWarning(offset: Offset, msg: String, since: String): Unit =
      currentRun.reporting.deprecationWarning(o2p(offset), msg, since)

    private var smartParsing = false
    @inline private def withSmartParsing[T](body: => T): T = {
      val saved = smartParsing
      smartParsing = true
      try body
      finally smartParsing = saved
    }
    def withPatches(patches: List[BracePatch]): UnitParser = new UnitParser(unit, patches)

    val syntaxErrors = new ListBuffer[(Int, String)]
    def showSyntaxErrors() =
      for ((offset, msg) <- syntaxErrors)
        reporter.error(o2p(offset), msg)

    override def syntaxError(offset: Offset, msg: String): Unit = {
      if (smartParsing) syntaxErrors += ((offset, msg))
      else reporter.error(o2p(offset), msg)
    }

    override def incompleteInputError(msg: String): Unit = {
      val offset = source.content.length - 1
      if (smartParsing) syntaxErrors += ((offset, msg))
      else currentRun.parsing.incompleteInputError(o2p(offset), msg)
    }

    /** parse unit. If there are unbalanced braces,
     *  try to correct them and reparse.
     */
    def smartParse(): Tree = withSmartParsing {
      val firstTry = parse()
      if (syntaxErrors.isEmpty) firstTry
      else in.healBraces() match {
        case Nil      => showSyntaxErrors() ; firstTry
        case patches  => (this withPatches patches).parse()
      }
    }
  }

  type Location = Int
  final val Local: Location = 0
  final val InBlock: Location = 1
  final val InTemplate: Location = 2

  // These symbols may not yet be loaded (e.g. in the ide) so don't go
  // through definitions to obtain the names.
  lazy val ScalaValueClassNames = Seq(tpnme.AnyVal,
      tpnme.Unit,
      tpnme.Boolean,
      tpnme.Byte,
      tpnme.Short,
      tpnme.Char,
      tpnme.Int,
      tpnme.Long,
      tpnme.Float,
      tpnme.Double)

  import nme.raw

  abstract class Parser extends ParserCommon { parser =>
    val in: Scanner
    def unit: CompilationUnit
    def source: SourceFile

    /** Scoping operator used to temporarily look into the future.
     *  Backs up scanner data before evaluating a block and restores it after.
     */
    @inline final def lookingAhead[T](body: => T): T = {
      val saved = new ScannerData {} copyFrom in
      in.nextToken()
      try body finally in copyFrom saved
    }

    /** Perform an operation while peeking ahead.
     *  Pushback if the operation yields an empty tree or blows to pieces.
     */
    @inline def peekingAhead(tree: =>Tree): Tree = {
      @inline def peekahead() = {
        in.prev copyFrom in
        in.nextToken()
      }
      @inline def pushback() = {
        in.next copyFrom in
        in copyFrom in.prev
      }
      peekahead()
      // try it, in case it is recoverable
      val res = try tree catch { case e: Exception => pushback() ; throw e }
      if (res.isEmpty) pushback()
      res
    }

    class ParserTreeBuilder extends TreeBuilder {
      val global: self.global.type = self.global
      def unit = parser.unit
      def source = parser.source
    }
    val treeBuilder = new ParserTreeBuilder
    import treeBuilder.{global => _, unit => _, source => _, fresh => _, _}

    implicit def fresh: FreshNameCreator = unit.fresh

    def o2p(offset: Offset): Position                          = Position.offset(source, offset)
    def r2p(start: Offset, mid: Offset, end: Offset): Position = rangePos(source, start, mid, end)
    def r2p(start: Offset, mid: Offset): Position              = r2p(start, mid, in.lastOffset max start)
    def r2p(offset: Offset): Position                          = r2p(offset, offset)

    /** whether a non-continuable syntax error has been seen */
    private var lastErrorOffset : Int = -1

    /** The types of the context bounds of type parameters of the surrounding class
     */
    private var classContextBounds: List[Tree] = Nil
    @inline private def savingClassContextBounds[T](op: => T): T = {
      val saved = classContextBounds
      try op
      finally classContextBounds = saved
    }


    /** Are we inside the Scala package? Set for files that start with package scala
     */
    private var inScalaPackage = false
    private var currentPackage = ""
    def resetPackage(): Unit = {
      inScalaPackage = false
      currentPackage = ""
    }
    private def inScalaRootPackage = inScalaPackage && currentPackage == "scala"

    def parseStartRule: () => Tree

    def parseRule[T](rule: this.type => T): T = {
      val t = rule(this)
      accept(EOF)
      t
    }

    /** This is the general parse entry point.
     */
    def parse(): Tree = parseRule(_.parseStartRule())

    /** These are alternative entry points for repl, script runner, toolbox and parsing in macros.
     */
    def parseStats(): List[Tree] = parseRule(_.templateStats())
    def parseStatsOrPackages(): List[Tree] = parseRule(_.templateOrTopStatSeq())

    /** This is the parse entry point for code which is not self-contained, e.g.
     *  a script which is a series of template statements.  They will be
     *  swaddled in Trees until the AST is equivalent to the one returned
     *  by compilationUnit().
     */
    def scriptBody(): Tree = {
      val stmts = parseStats()

      def mainModuleName = newTermName(settings.script.value)

      /* If there is only a single object template in the file and it has a
       * suitable main method, we will use it rather than building another object
       * around it.  Since objects are loaded lazily the whole script would have
       * been a no-op, so we're not taking much liberty.
       */
      def searchForMain(): Tree = {
        import PartialFunction.cond

        /* Have to be fairly liberal about what constitutes a main method since
         * nothing has been typed yet - for instance we can't assume the parameter
         * type will look exactly like "Array[String]" as it could have been renamed
         * via import, etc.
         */
        def isMainMethod(t: Tree) = t match {
          case DefDef(_, nme.main, Nil, List(_), _, _)  => true
          case _                                        => false
        }
        def isApp(t: Tree) = t match {
          case Template(parents, _, _) => parents.exists(cond(_) { case Ident(tpnme.App) => true })
          case _ => false
        }
        /* We allow only one main module. */
        var seenModule = false
        var disallowed = EmptyTree: Tree
        val newStmts = stmts.map {
          case md @ ModuleDef(mods, name, template) if !seenModule && (isApp(template) || md.exists(isMainMethod)) =>
            seenModule = true
            /* This slightly hacky situation arises because we have no way to communicate
             * back to the scriptrunner what the name of the program is.  Even if we were
             * willing to take the sketchy route of settings.script.value = progName, that
             * does not work when using fsc.  And to find out in advance would impose a
             * whole additional parse.  So instead, if the actual object's name differs from
             * what the script is expecting, we transform it to match.
             */
            if (name == mainModuleName) md
            else treeCopy.ModuleDef(md, mods, mainModuleName, template)
          case md @ ModuleDef(_, _, _)   => md
          case cd @ ClassDef(_, _, _, _) => cd
          case t  @ Import(_, _)         => t
          case t =>
            /* If we see anything but the above, fail. */
            if (disallowed.isEmpty) disallowed = t
            EmptyTree
        }
        if (disallowed.isEmpty) makeEmptyPackage(0, newStmts)
        else {
          if (seenModule)
            warning(disallowed.pos.point, "Script has a main object but statement is disallowed")
          EmptyTree
        }
      }

      def mainModule: Tree =
        if (mainModuleName == newTermName(ScriptRunner.defaultScriptMain)) searchForMain() else EmptyTree

      def repackaged: Tree = {
        /*  Here we are building an AST representing the following source fiction,
         *  where `moduleName` is from -Xscript (defaults to "Main") and <stmts> are
         *  the result of parsing the script file.
         *
         *  {{{
         *  object moduleName {
         *    def main(args: Array[String]): Unit =
         *      new AnyRef {
         *        stmts
         *      }
         *  }
         *  }}}
         */
        def emptyInit   = DefDef(
          NoMods,
          nme.CONSTRUCTOR,
          Nil,
          ListOfNil,
          TypeTree(),
          Block(List(Apply(gen.mkSuperInitCall, Nil)), literalUnit)
        )

        // def main
        def mainParamType = AppliedTypeTree(Ident(tpnme.Array), List(Ident(tpnme.String)))
        def mainParameter = List(ValDef(Modifiers(Flags.PARAM), nme.args, mainParamType, EmptyTree))
        def mainDef       = DefDef(NoMods, nme.main, Nil, List(mainParameter), scalaDot(tpnme.Unit), gen.mkAnonymousNew(stmts))

        // object Main
        def moduleName  = newTermName(ScriptRunner scriptMain settings)
        def moduleBody  = Template(atInPos(scalaAnyRefConstr) :: Nil, noSelfType, List(emptyInit, mainDef))
        def moduleDef   = ModuleDef(NoMods, moduleName, moduleBody)

        // package <empty> { ... }
        makeEmptyPackage(0, moduleDef :: Nil)
      }

      mainModule orElse repackaged
    }

/* --------------- PLACEHOLDERS ------------------------------------------- */

    /** The implicit parameters introduced by `_` in the current expression.
     *  Parameters appear in reverse order.
     */
    var placeholderParams: List[ValDef] = Nil

    /** The placeholderTypes introduced by `_` in the current type.
     *  Parameters appear in reverse order.
     */
    var placeholderTypes: List[TypeDef] = Nil

    def checkNoEscapingPlaceholders[T](op: => T): T = {
      val savedPlaceholderParams = placeholderParams
      val savedPlaceholderTypes = placeholderTypes
      placeholderParams = List()
      placeholderTypes = List()

      val res = op

      placeholderParams match {
        case vd :: _ =>
          syntaxError(vd.pos, "unbound placeholder parameter", skipIt = false)
          placeholderParams = List()
        case _ =>
      }
      placeholderTypes match {
        case td :: _ =>
          syntaxError(td.pos, "unbound wildcard type", skipIt = false)
          placeholderTypes = List()
        case _ =>
      }
      placeholderParams = savedPlaceholderParams
      placeholderTypes = savedPlaceholderTypes

      res
    }

    def placeholderTypeBoundary(op: => Tree): Tree = {
      val savedPlaceholderTypes = placeholderTypes
      placeholderTypes = List()
      var t = op
      if (!placeholderTypes.isEmpty && t.isInstanceOf[AppliedTypeTree]) {
        val expos = t.pos
        ensureNonOverlapping(t, placeholderTypes)
        t = atPos(expos) { ExistentialTypeTree(t, placeholderTypes.reverse) }
        placeholderTypes = List()
      }
      placeholderTypes = placeholderTypes ::: savedPlaceholderTypes
      t
    }

    @tailrec
    final def isWildcard(t: Tree): Boolean = t match {
      case Ident(name1) => !placeholderParams.isEmpty && name1 == placeholderParams.head.name
      case Typed(t1, _) => isWildcard(t1)
      case Annotated(t1, _) => isWildcard(t1)
      case _ => false
    }

/* ------------- ERROR HANDLING ------------------------------------------- */

    val assumedClosingParens = mutable.Map(RPAREN -> 0, RBRACKET -> 0, RBRACE -> 0)

    private var inFunReturnType = false
    @inline private def fromWithinReturnType[T](body: => T): T = {
      val saved = inFunReturnType
      inFunReturnType = true
      try body
      finally inFunReturnType = saved
    }

    protected def skip(targetToken: Token): Unit = {
      var nparens = 0
      var nbraces = 0
      while (true) {
        in.token match {
          case EOF =>
            return
          case SEMI =>
            if (nparens == 0 && nbraces == 0) return
          case NEWLINE =>
            if (nparens == 0 && nbraces == 0) return
          case NEWLINES =>
            if (nparens == 0 && nbraces == 0) return
          case RPAREN =>
            nparens -= 1
          case RBRACE =>
            if (nbraces == 0) return
            nbraces -= 1
          case LPAREN =>
            nparens += 1
          case LBRACE =>
            nbraces += 1
          case _ =>
        }
        if (targetToken == in.token && nparens == 0 && nbraces == 0) return
        in.nextToken()
      }
    }
    def warning(offset: Offset, msg: String): Unit
    def incompleteInputError(msg: String): Unit
    def syntaxError(offset: Offset, msg: String): Unit

    private def syntaxError(pos: Position, msg: String, skipIt: Boolean): Unit =
      syntaxError(pos pointOrElse in.offset, msg, skipIt)
    def syntaxError(msg: String, skipIt: Boolean): Unit =
      syntaxError(in.offset, msg, skipIt)

    def syntaxError(offset: Offset, msg: String, skipIt: Boolean): Unit = {
      if (offset > lastErrorOffset) {
        syntaxError(offset, msg)
        lastErrorOffset = in.offset         // no more errors on this token.
      }
      if (skipIt)
        skip(UNDEF)
    }

    def warning(msg: String): Unit = warning(in.offset, msg)

    def syntaxErrorOrIncomplete(msg: String, skipIt: Boolean): Unit = {
      if (in.token == EOF)
        incompleteInputError(msg)
      else
        syntaxError(in.offset, msg, skipIt)
    }
    def syntaxErrorOrIncompleteAnd[T](msg: String, skipIt: Boolean)(and: T): T = {
      syntaxErrorOrIncomplete(msg, skipIt)
      and
    }

    def expectedMsgTemplate(exp: String, fnd: String) = s"$exp expected but $fnd found."
    def expectedMsg(token: Token): String =
      in.token match {
        case NEWLINE | NEWLINES => s"${token2string(token)} expected."
        case actual             => expectedMsgTemplate(token2string(token), token2string(actual))
      }

    /** Consume one token of the specified type, or signal an error if it is not there. */
    def accept(token: Token): Offset = {
      val offset = in.offset
      if (in.token != token) {
        syntaxErrorOrIncomplete(expectedMsg(token), skipIt = false)
        if ((token == RPAREN || token == RBRACE || token == RBRACKET))
          if (in.parenBalance(token) + assumedClosingParens(token) < 0)
            assumedClosingParens(token) += 1
          else
            skip(token)
        else
          skip(UNDEF)
      }
      if (in.token == token) in.nextToken()
      offset
    }

    /** {{{
     *  semi = nl {nl} | `;`
     *  nl  = `\n` // where allowed
     *  }}}
     */
    def acceptStatSep(): Unit = in.token match {
      case NEWLINE | NEWLINES => in.nextToken()
      case _                  => accept(SEMI)
    }
    def acceptStatSepOpt() =
      if (!isStatSeqEnd)
        acceptStatSep()

    def errorTypeTree    = setInPos(TypeTree() setType ErrorType)
    def errorTermTree    = setInPos(newLiteral(null))
    def errorPatternTree = setInPos(Ident(nme.WILDCARD))

    /** Check that type parameter is not by name or repeated. */
    def checkNotByNameOrVarargs(tpt: Tree) = {
      if (treeInfo isByNameParamType tpt)
        syntaxError(tpt.pos, "no by-name parameter type allowed here", skipIt = false)
      else if (treeInfo isRepeatedParamType tpt)
        syntaxError(tpt.pos, "no * parameter type allowed here", skipIt = false)
    }

/* -------------- TOKEN CLASSES ------------------------------------------- */

    def isModifier: Boolean = in.token match {
      case ABSTRACT | FINAL | SEALED | PRIVATE |
           PROTECTED | OVERRIDE | IMPLICIT | LAZY => true
      case _ => false
    }

    def isAnnotation: Boolean = in.token == AT

    def isLocalModifier: Boolean = in.token match {
      case ABSTRACT | FINAL | SEALED | IMPLICIT | LAZY => true
      case _ => false
    }

    def isTemplateIntro: Boolean = in.token match {
      case OBJECT | CASEOBJECT | CLASS | CASECLASS | TRAIT  => true
      case _                                                => false
    }
    def isDclIntro: Boolean = in.token match {
      case VAL | VAR | DEF | TYPE => true
      case _ => false
    }

    def isDefIntro = isTemplateIntro || isDclIntro

    def isNumericLit: Boolean = in.token match {
      case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT => true
      case _ => false
    }

    def isIdentExcept(except: Name) = isIdent && in.name != except
    def isIdentOf(name: Name)       = isIdent && in.name == name

    def isUnaryOp  = isIdent && raw.isUnary(in.name)
    def isRawStar  = isRawIdent && in.name == raw.STAR
    def isRawBar   = isRawIdent && in.name == raw.BAR
    def isRawIdent = in.token == IDENTIFIER

    def isIdent = in.token == IDENTIFIER || in.token == BACKQUOTED_IDENT
    def isMacro = in.token == IDENTIFIER && in.name == nme.MACROkw

    def isLiteralToken(token: Token) = token match {
      case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT |
           STRINGLIT | INTERPOLATIONID | SYMBOLLIT | TRUE | FALSE | NULL => true
      case _                                                        => false
    }
    def isLiteral = isLiteralToken(in.token)

    def isSimpleExprIntroToken(token: Token): Boolean = isLiteralToken(token) || (token match {
      case IDENTIFIER | BACKQUOTED_IDENT |
           THIS | SUPER | NEW | USCORE |
           LPAREN | LBRACE | XMLSTART => true
      case _ => false
    })

    def isSimpleExprIntro: Boolean = isExprIntroToken(in.token)

    def isExprIntroToken(token: Token): Boolean = isLiteralToken(token) || (token match {
      case IDENTIFIER | BACKQUOTED_IDENT |
           THIS | SUPER | IF | FOR | NEW | USCORE | TRY | WHILE |
           DO | RETURN | THROW | LPAREN | LBRACE | XMLSTART => true
      case _ => false
    })

    def isExprIntro: Boolean = isExprIntroToken(in.token)

    def isTypeIntroToken(token: Token): Boolean = (isLiteralToken(token) && token != NULL) || (token match {
      case IDENTIFIER | BACKQUOTED_IDENT | THIS |
           SUPER | USCORE | LPAREN | AT => true
      case _ => false
    })

    def isStatSeqEnd = in.token == RBRACE || in.token == EOF

    def isCaseDefEnd = in.token == RBRACE || in.token == CASE || in.token == EOF

    def isStatSep(token: Token): Boolean =
      token == NEWLINE || token == NEWLINES || token == SEMI

    def isStatSep: Boolean = isStatSep(in.token)


/* --------- COMMENT AND ATTRIBUTE COLLECTION ----------------------------- */

    /** A hook for joining the comment associated with a definition.
     *  Overridden by scaladoc.
     */
    def joinComment(trees: => List[Tree]): List[Tree] = trees

/* ---------- TREE CONSTRUCTION ------------------------------------------- */

    def atPos[T <: Tree](offset: Offset)(t: T): T                            = atPos(r2p(offset))(t)
    def atPos[T <: Tree](start: Offset, point: Offset)(t: T): T              = atPos(r2p(start, point))(t)
    def atPos[T <: Tree](start: Offset, point: Offset, end: Offset)(t: T): T = atPos(r2p(start, point, end))(t)
    def atPos[T <: Tree](pos: Position)(t: T): T                             = global.atPos(pos)(t)

    def atInPos[T <: Tree](t: T): T  = atPos(o2p(in.offset))(t)
    def setInPos[T <: Tree](t: T): T = t setPos o2p(in.offset)

    /** Convert tree to formal parameter list. */
    def convertToParams(tree: Tree): List[ValDef] = tree match {
      case Parens(ts) => ts map convertToParam
      case _          => List(convertToParam(tree))
    }

    /** Convert tree to formal parameter. */
    def convertToParam(tree: Tree): ValDef = atPos(tree.pos) {
      def removeAsPlaceholder(name: Name): Unit = {
        placeholderParams = placeholderParams filter (_.name != name)
      }
      def errorParam = makeParam(nme.ERROR, errorTypeTree setPos o2p(tree.pos.end))
      def propagateNoWarnAttachment(from: Tree, to: ValDef): to.type =
        if (from.hasAttachment[NoWarnAttachment.type]) to.updateAttachment(NoWarnAttachment)
        else to
      tree match {
        case id @ Ident(name) =>
          removeAsPlaceholder(name)
          propagateNoWarnAttachment(id, makeParam(name.toTermName, TypeTree() setPos o2p(tree.pos.end)))
        case Typed(id @ Ident(name), tpe) if tpe.isType => // get the ident!
          removeAsPlaceholder(name)
          propagateNoWarnAttachment(id, makeParam(name.toTermName, tpe))
        case build.SyntacticTuple(as) =>
          val arity = as.length
          val example = analyzer.exampleTuplePattern(as map { case Ident(name) => name; case _ => nme.EMPTY })
          val msg =
            sm"""|not a legal formal parameter.
                 |Note: Tuples cannot be directly destructured in method or function parameters.
                 |      Either create a single parameter accepting the Tuple${arity},
                 |      or consider a pattern matching anonymous function: `{ case $example => ... }"""
          syntaxError(tree.pos, msg, skipIt = false)
          errorParam
        case _ =>
          syntaxError(tree.pos, "not a legal formal parameter", skipIt = false)
          errorParam
      }
    }

    /** Convert (qual)ident to type identifier. */
    def convertToTypeId(tree: Tree): Tree = atPos(tree.pos) {
      convertToTypeName(tree) getOrElse {
        syntaxError(tree.pos, "identifier expected", skipIt = false)
        errorTypeTree
      }
    }

    /** {{{ part { `sep` part } }}},or if sepFirst is true, {{{ { `sep` part } }}}. */
    final def tokenSeparated[T](separator: Token, sepFirst: Boolean, part: => T): List[T] = {
      val ts = new ListBuffer[T]
      if (!sepFirst)
        ts += part

      while (in.token == separator) {
        in.nextToken()
        ts += part
      }
      ts.toList
    }
    @inline final def commaSeparated[T](part: => T): List[T] = tokenSeparated(COMMA, sepFirst = false, part)
    @inline final def caseSeparated[T](part: => T): List[T] = tokenSeparated(CASE, sepFirst = true, part)
    def readAnnots(part: => Tree): List[Tree] = tokenSeparated(AT, sepFirst = true, part)

    /** Create a tuple type Tree. If the arity is not supported, a syntax error is emitted. */
    def makeSafeTupleType(elems: List[Tree]) = {
      if (checkTupleSize(elems)) makeTupleType(elems)
      else makeTupleType(Nil) // create a dummy node; makeTupleType(elems) would fail
    }

    /** Create a tuple term Tree. If the arity is not supported, a syntax error is emitted. */
    def makeSafeTupleTerm(elems: List[Tree]) = {
      checkTupleSize(elems)
      makeTupleTerm(elems)
    }

    /** Create a function Tree. If the arity is not supported, a syntax error is emitted. */
    def makeSafeFunctionType(argtpes: List[Tree], restpe: Tree) = {
      if (checkFunctionArity(argtpes)) makeFunctionTypeTree(argtpes, restpe)
      else makeFunctionTypeTree(Nil, restpe) // create a dummy node
    }

    private[this] def checkTupleSize(elems: List[Tree]): Boolean =
      elems.lengthCompare(definitions.MaxTupleArity) <= 0 || {
        val firstInvalidElem = elems(definitions.MaxTupleArity)
        val msg = s"tuples may not have more than ${definitions.MaxFunctionArity} elements, but ${elems.length} given"
        syntaxError(firstInvalidElem.pos, msg, skipIt = false)
        false
      }

    private[this] def checkFunctionArity(argtpes: List[Tree]): Boolean =
      argtpes.lengthCompare(definitions.MaxFunctionArity) <= 0 || {
        val firstInvalidArg = argtpes(definitions.MaxFunctionArity)
        val msg = s"function values may not have more than ${definitions.MaxFunctionArity} parameters, but ${argtpes.length} given"
        syntaxError(firstInvalidArg.pos, msg, skipIt = false)
        false
      }

    /** Strip the artificial `Parens` node to create a tuple term Tree. */
    def stripParens(t: Tree) = t match {
      case Parens(ts) => atPos(t.pos) { makeSafeTupleTerm(ts) }
      case _ => t
    }

    /** Create tree representing (unencoded) binary operation expression or pattern. */
    def makeBinop(isExpr: Boolean, left: Tree, op: TermName, right: Tree, opPos: Position, targs: List[Tree] = Nil): Tree = {
      require(isExpr || targs.isEmpty || targs.exists(_.isErroneous),
        s"Incompatible args to makeBinop: !isExpr but targs=$targs")

      val rightAssoc = !nme.isLeftAssoc(op)

      def mkSelection(t: Tree) = {
        val pos = (opPos union t.pos) makeTransparentIf rightAssoc
        val sel = atPos(pos)(Select(stripParens(t), op.encode))
        if (targs.isEmpty) sel
        else {
          /* if it's right-associative, `targs` are between `op` and `t` so make the pos transparent */
          atPos((pos union targs.last.pos) makeTransparentIf rightAssoc) {
            TypeApply(sel, targs)
          }
        }
      }
      def mkNamed(args: List[Tree]) = if (isExpr) args map treeInfo.assignmentToMaybeNamedArg else args
      val arguments = right match {
        case Parens(Nil)  => Literal(Constant(())) :: Nil
        case Parens(args) => mkNamed(args)
        case _            => right :: Nil
      }
      if (isExpr) {
        if (rightAssoc) {
          import symtab.Flags._
          val x = freshTermName(nme.RIGHT_ASSOC_OP_PREFIX)
          val liftedArg = atPos(left.pos) {
            ValDef(Modifiers(FINAL | SYNTHETIC | ARTIFACT), x, TypeTree(), stripParens(left))
          }
          Block(
            liftedArg :: Nil,
            Apply(mkSelection(right), List(Ident(x) setPos left.pos.focus)))
        } else {
          Apply(mkSelection(left), arguments)
        }
      } else {
        Apply(Ident(op.encode), stripParens(left) :: arguments)
      }
    }

    /* --------- OPERAND/OPERATOR STACK --------------------------------------- */

    /** Modes for infix types. */
    object InfixMode extends Enumeration {
      val FirstOp, LeftOp, RightOp = Value
    }

    var opstack: List[OpInfo] = Nil

    @deprecated("Use `scala.reflect.internal.Precedence`", "2.11.0")
    def precedence(operator: Name): Int = Precedence(operator.toString).level

    private def opHead = opstack.head
    private def headPrecedence = opHead.precedence
    private def popOpInfo(): OpInfo = try opHead finally opstack = opstack.tail
    private def pushOpInfo(top: Tree): Unit = {
      val name   = in.name
      val offset = in.offset
      ident()
      val targs = if (in.token == LBRACKET) exprTypeArgs() else Nil
      val opinfo = OpInfo(top, name, targs, offset)
      opstack ::= opinfo
    }

    def checkHeadAssoc(leftAssoc: Boolean) = checkAssoc(opHead.offset, opHead.operator, leftAssoc)
    def checkAssoc(offset: Offset, op: Name, leftAssoc: Boolean) = (
      if (nme.isLeftAssoc(op) != leftAssoc)
        syntaxError(offset, "left- and right-associative operators with same precedence may not be mixed", skipIt = false)
    )

    def finishPostfixOp(start: Int, base: List[OpInfo], opinfo: OpInfo): Tree = {
      if (opinfo.targs.nonEmpty)
        syntaxError(opinfo.offset, "type application is not allowed for postfix operators")

      val od = stripParens(reduceExprStack(base, opinfo.lhs))
      makePostfixSelect(start, opinfo.offset, od, opinfo.operator)
    }

    def finishBinaryOp(isExpr: Boolean, opinfo: OpInfo, rhs: Tree): Tree = {
      import opinfo._
      val operatorPos: Position = Position.range(rhs.pos.source, offset, offset, offset + operator.length)
      val pos                   = lhs.pos union rhs.pos union operatorPos withPoint offset

      atPos(pos)(makeBinop(isExpr, lhs, operator, rhs, operatorPos, opinfo.targs))
    }

    def reduceExprStack(base: List[OpInfo], top: Tree): Tree    = reduceStack(isExpr = true, base, top)
    def reducePatternStack(base: List[OpInfo], top: Tree): Tree = reduceStack(isExpr = false, base, top)

    def reduceStack(isExpr: Boolean, base: List[OpInfo], top: Tree): Tree = {
      val opPrecedence = if (isIdent) Precedence(in.name.toString) else Precedence(0)
      val leftAssoc    = !isIdent || (nme isLeftAssoc in.name)

      reduceStack(isExpr, base, top, opPrecedence, leftAssoc)
    }

    def reduceStack(isExpr: Boolean, base: List[OpInfo], top: Tree, opPrecedence: Precedence, leftAssoc: Boolean): Tree = {
      def isDone          = opstack == base
      def lowerPrecedence = !isDone && (opPrecedence < headPrecedence)
      def samePrecedence  = !isDone && (opPrecedence == headPrecedence)
      def canReduce       = lowerPrecedence || leftAssoc && samePrecedence

      if (samePrecedence)
        checkHeadAssoc(leftAssoc)

      @tailrec
      def loop(top: Tree): Tree = if (canReduce) {
        val info = popOpInfo()
        if (!isExpr && info.targs.nonEmpty) {
          syntaxError(info.offset, "type application is not allowed in pattern")
          info.targs.foreach(_.setType(ErrorType))
        }
        loop(finishBinaryOp(isExpr, info, top))
      } else top

      loop(top)
    }

/* -------- IDENTIFIERS AND LITERALS ------------------------------------------- */

    /** Methods which implicitly propagate the context in which they were
     *  called: either in a pattern context or not.  Formerly, this was
     *  threaded through numerous methods as boolean isPattern.
     */
    sealed trait PatternContextSensitive {
      /** {{{
       *  ArgType       ::=  Type
       *  }}}
       */
      def argType(): Tree
      def functionArgType(): Tree

      private def tupleInfixType(start: Offset) = {
        in.nextToken()
        if (in.token == RPAREN) {
          in.nextToken()
          atPos(start, accept(ARROW)) { makeFunctionTypeTree(Nil, typ()) }
        }
        else {
          val ts = functionTypes()
          accept(RPAREN)
          if (in.token == ARROW)
            atPos(start, in.skipToken()) { makeSafeFunctionType(ts, typ()) }
          else {
            ts foreach checkNotByNameOrVarargs
            val tuple = atPos(start) { makeSafeTupleType(ts) }
            infixTypeRest(
              compoundTypeRest(
                annotTypeRest(
                  simpleTypeRest(
                    tuple))),
              InfixMode.FirstOp
            )
          }
        }
      }
      private def makeExistentialTypeTree(t: Tree) = {
        // EmptyTrees in the result of refinement() stand for parse errors
        // so it's okay for us to filter them out here
        ExistentialTypeTree(t, refinement() flatMap {
          case t @ TypeDef(_, _, _, TypeBoundsTree(_, _)) => Some(t)
          case t @ ValDef(_, _, _, EmptyTree) => Some(t)
          case EmptyTree => None
          case _ => syntaxError(t.pos, "not a legal existential clause", skipIt = false); None
        })
      }

      /** {{{
       *  Type ::= InfixType `=>` Type
       *         | `(` [`=>` Type] `)` `=>` Type
       *         | InfixType [ExistentialClause]
       *  ExistentialClause ::= forSome `{` ExistentialDcl {semi ExistentialDcl} `}`
       *  ExistentialDcl    ::= type TypeDcl | val ValDcl
       *  }}}
       */
      def typ(): Tree = placeholderTypeBoundary {
        val start = in.offset
        val t =
          if (in.token == LPAREN) tupleInfixType(start)
          else infixType(InfixMode.FirstOp)

        in.token match {
          case ARROW    => atPos(start, in.skipToken()) { makeFunctionTypeTree(List(t), typ()) }
          case FORSOME  => atPos(start, in.skipToken()) { makeExistentialTypeTree(t) }
          case _        => t
        }
      }

      /** {{{
       *  TypeArgs    ::= `[` ArgType {`,` ArgType} `]`
       *  }}}
       */
      def typeArgs(): List[Tree] = inBrackets(types())

      /** {{{
       *  AnnotType        ::=  SimpleType {Annotation}
       *  }}}
       */
      def annotType(): Tree = placeholderTypeBoundary { annotTypeRest(simpleType()) }

      /** {{{
       *  SimpleType       ::=  SimpleType TypeArgs
       *                     |  SimpleType `#` Id
       *                     |  StableId
       *                     |  Path `.` type
       *                     |  Literal
       *                     |  `(` Types `)`
       *                     |  WildcardType
       *  }}}
       */
      def simpleType(): Tree = {
        if (isLiteralToken(in.token) && in.token != NULL)
          atPos(in.offset){SingletonTypeTree(literal())}
        else if (in.name == raw.MINUS && lookingAhead(isNumericLit)) {
          val start = in.offset
          in.nextToken()
          atPos(start){ SingletonTypeTree(literal(isNegated = true, start = start)) }
        } else {
          val start = in.offset
          simpleTypeRest(in.token match {
            case LPAREN   => atPos(start)(makeSafeTupleType(inParens(types())))
            case USCORE   => wildcardType(in.skipToken())
            case _        =>
              path(thisOK = false, typeOK = true) match {
                case r @ SingletonTypeTree(_) => r
                case r => convertToTypeId(r)
              }
          })
        }
      }

      private def typeProjection(t: Tree): Tree = {
        val hashOffset = in.skipToken()
        val nameOffset = in.offset
        val name       = identForType(skipIt = false)
        val point      = if (name == tpnme.ERROR) hashOffset else nameOffset
        atPos(t.pos.start, point)(SelectFromTypeTree(t, name))
      }
      @tailrec
      final def simpleTypeRest(t: Tree): Tree = in.token match {
        case HASH     => simpleTypeRest(typeProjection(t))
        case LBRACKET => simpleTypeRest(atPos(t.pos.start, t.pos.point)(AppliedTypeTree(t, typeArgs())))
        case _        => t
      }

      /** {{{
       *  CompoundType ::= AnnotType {with AnnotType} [Refinement]
       *                |  Refinement
       *  }}}
       */
      def compoundType(): Tree = compoundTypeRest(
        if (in.token == LBRACE) atInPos(scalaAnyRefConstr)
        else annotType()
      )

      def compoundTypeRest(t: Tree): Tree = {
        val ts = new ListBuffer[Tree] += t
        while (in.token == WITH) {
          in.nextToken()
          ts += annotType()
        }
        newLineOptWhenFollowedBy(LBRACE)
        val types         = ts.toList
        val braceOffset   = in.offset
        val hasRefinement = in.token == LBRACE
        val refinements   = if (hasRefinement) refinement() else Nil
        // Warn if they are attempting to refine Unit; we can't be certain it's
        // scala.Unit they're refining because at this point all we have is an
        // identifier, but at a later stage we lose the ability to tell an empty
        // refinement from no refinement at all.  See bug #284.
        if (hasRefinement) types match {
          case Ident(name) :: Nil if name endsWith "Unit" => warning(braceOffset, "Detected apparent refinement of Unit; are you missing an '=' sign?")
          case _                                          =>
        }
        // The second case includes an empty refinement - refinements is empty, but
        // it still gets a CompoundTypeTree.
        ts.toList match {
          case tp :: Nil if !hasRefinement => tp  // single type, no refinement, already positioned
          case tps                         => atPos(t.pos.start)(CompoundTypeTree(Template(tps, noSelfType, refinements)))
        }
      }

      def infixTypeRest(t: Tree, mode: InfixMode.Value): Tree = {
        // Detect postfix star for repeated args.
        // Only RPAREN can follow, but accept COMMA and EQUALS for error's sake.
        // Take RBRACE as a paren typo.
        def checkRepeatedParam = if (isRawStar) {
          lookingAhead (in.token match {
            case RPAREN | COMMA | EQUALS | RBRACE => t
            case _                                => EmptyTree
          })
        } else EmptyTree
        def asInfix = {
          val opOffset  = in.offset
          val leftAssoc = nme.isLeftAssoc(in.name)
          if (mode != InfixMode.FirstOp)
            checkAssoc(opOffset, in.name, leftAssoc = mode == InfixMode.LeftOp)
          val tycon = atPos(opOffset) { Ident(identForType()) }
          newLineOptWhenFollowing(isTypeIntroToken)
          def mkOp(t1: Tree) = atPos(t.pos.start, opOffset) { AppliedTypeTree(tycon, List(t, t1)) }
          if (leftAssoc)
            infixTypeRest(mkOp(compoundType()), InfixMode.LeftOp)
          else
            mkOp(infixType(InfixMode.RightOp))
        }
        if (isIdent) checkRepeatedParam orElse asInfix
        else t
      }

      /** {{{
       *  InfixType ::= CompoundType {id [nl] CompoundType}
       *  }}}
       */
      def infixType(mode: InfixMode.Value): Tree =
        placeholderTypeBoundary { infixTypeRest(compoundType(), mode) }

      /** {{{
       *  Types ::= Type {`,` Type}
       *  }}}
       */
      def types(): List[Tree] = commaSeparated(argType())
      def functionTypes(): List[Tree] = commaSeparated(functionArgType())
    }

    /** Assumed (provisionally) to be TermNames. */
    def ident(skipIt: Boolean): Name = (
      if (isIdent) {
        val name = in.name.encode
        in.nextToken()
        name
      }
      else syntaxErrorOrIncompleteAnd(expectedMsg(IDENTIFIER), skipIt)(nme.ERROR)
    )

    def ident(): Name = ident(skipIt = true)
    def rawIdent(): Name = try in.name finally in.nextToken()

    /** For when it's known already to be a type name. */
    def identForType(): TypeName = ident().toTypeName
    def identForType(skipIt: Boolean): TypeName = ident(skipIt).toTypeName

    def identOrMacro(): Name = if (isMacro) rawIdent() else ident()

    def selector(t: Tree): Tree = {
      val point = if (isIdent) in.offset else in.lastOffset //scala/bug#8459
      //assert(t.pos.isDefined, t)
      if (t != EmptyTree)
        Select(t, ident(skipIt = false)) setPos r2p(t.pos.start, point, in.lastOffset)
      else
        errorTermTree // has already been reported
    }

    /** {{{
     *  Path       ::= StableId
     *              |  [Ident `.`] this
     *  AnnotType ::= Path [`.` type]
     *  }}}
     */
    def path(thisOK: Boolean, typeOK: Boolean): Tree = {
      val start = in.offset
      var t: Tree = null
      if (in.token == THIS) {
        in.nextToken()
        t = atPos(start) { This(tpnme.EMPTY) }
        if (!thisOK || in.token == DOT) {
          t = selectors(t, typeOK, accept(DOT))
        }
      } else if (in.token == SUPER) {
        in.nextToken()
        t = atPos(start) { Super(This(tpnme.EMPTY), mixinQualifierOpt()) }
        accept(DOT)
        t = selector(t)
        if (in.token == DOT) t = selectors(t, typeOK, in.skipToken())
      } else {
        val tok = in.token
        val name = ident()
        t = atPos(start) {
          if (tok == BACKQUOTED_IDENT) Ident(name) updateAttachment BackquotedIdentifierAttachment
          else Ident(name)
        }
        if (in.token == DOT) {
          val dotOffset = in.skipToken()
          if (in.token == THIS) {
            in.nextToken()
            t = atPos(start) { This(name.toTypeName) }
            if (!thisOK || in.token == DOT)
              t = selectors(t, typeOK, accept(DOT))
          } else if (in.token == SUPER) {
            in.nextToken()
            t = atPos(start) { Super(This(name.toTypeName), mixinQualifierOpt()) }
            accept(DOT)
            t = selector(t)
            if (in.token == DOT) t = selectors(t, typeOK, in.skipToken())
          } else {
            t = selectors(t, typeOK, dotOffset)
          }
        }
      }
      t
    }

    @tailrec
    final def selectors(t: Tree, typeOK: Boolean, dotOffset: Offset): Tree =
      if (typeOK && in.token == TYPE) {
        in.nextToken()
        atPos(t.pos.start, dotOffset) { SingletonTypeTree(t) }
      }
      else {
        val t1 = selector(t)
        if (in.token == DOT) { selectors(t1, typeOK, in.skipToken()) }
        else t1
      }

    /** {{{
    *   MixinQualifier ::= `[` Id `]`
    *   }}}
    */
    def mixinQualifierOpt(): TypeName =
      if (in.token == LBRACKET) inBrackets(identForType())
      else tpnme.EMPTY

    /** {{{
     *  StableId ::= Id
     *            |  Path `.` Id
     *            |  [id `.`] super [`[` id `]`]`.` id
     *  }}}
     */
    def stableId(): Tree =
      path(thisOK = false, typeOK = false)

    /** {{{
    *   QualId ::= Id {`.` Id}
    *   }}}
    */
    def qualId(): Tree = {
      val start = in.offset
      val id = atPos(start) { Ident(ident()) }
      if (in.token == DOT) { selectors(id, typeOK = false, in.skipToken()) }
      else id
    }
    /** Calls `qualId()` and manages some package state. */
    private def pkgQualId() = {
      if (in.token == IDENTIFIER && in.name.encode == nme.scala_)
        inScalaPackage = true

      val pkg = qualId()
      newLineOptWhenFollowedBy(LBRACE)

      if (currentPackage == "") currentPackage = pkg.toString
      else currentPackage = currentPackage + "." + pkg

      pkg
    }

    /** {{{
     *  SimpleExpr    ::= literal
     *                  | symbol
     *                  | null
     *  }}}
     */
    def literal(isNegated: Boolean = false, inPattern: Boolean = false, start: Offset = in.offset): Tree = atPos(start) {
      def finish(value: Any): Tree = try newLiteral(value) finally in.nextToken()
      if (in.token == INTERPOLATIONID)
        interpolatedString(inPattern = inPattern)
      else if (in.token == SYMBOLLIT) {
        def msg(what: String) =
          s"""symbol literal is $what; use Symbol("${in.strVal}") instead"""
        if (settings.isScala214) syntaxError(in.offset, msg("unsupported"))
        else deprecationWarning(in.offset, msg("deprecated"), "2.13.0")
        Apply(scalaDot(nme.Symbol), List(finish(in.strVal)))
      }
      else finish(in.token match {
        case CHARLIT                => in.charVal
        case INTLIT                 => in.intVal(isNegated).toInt
        case LONGLIT                => in.intVal(isNegated)
        case FLOATLIT               => in.floatVal(isNegated)
        case DOUBLELIT              => in.doubleVal(isNegated)
        case STRINGLIT | STRINGPART => in.strVal.intern()
        case TRUE                   => true
        case FALSE                  => false
        case NULL                   => null
        case _                      => syntaxErrorOrIncompleteAnd("illegal literal", skipIt = true)(null)
      })
    }

    /** Handle placeholder syntax.
     *  If evaluating the tree produces placeholders, then make it a function.
     */
    private def withPlaceholders(tree: =>Tree, isAny: Boolean): Tree = {
      val savedPlaceholderParams = placeholderParams
      placeholderParams = List()
      var res = tree
      if (placeholderParams.nonEmpty && !isWildcard(res)) {
        res = atPos(res.pos)(Function(placeholderParams.reverse, res))
        if (isAny) placeholderParams foreach (_.tpt match {
          case tpt @ TypeTree() => tpt setType definitions.AnyTpe
          case _                => // some ascription
        })
        placeholderParams = List()
      }
      placeholderParams = placeholderParams ::: savedPlaceholderParams
      res
    }

    /** Consume a USCORE and create a fresh synthetic placeholder param. */
    private def freshPlaceholder(): Tree = {
      val start = in.offset
      val pname = freshTermName()
      in.nextToken()
      val id = atPos(start)(Ident(pname))
      val param = atPos(id.pos.focus)(gen.mkSyntheticParam(pname.toTermName))
      placeholderParams = param :: placeholderParams
      id.updateAttachment(NoWarnAttachment)
    }

    private def interpolatedString(inPattern: Boolean): Tree = {
      def errpolation() = syntaxErrorOrIncompleteAnd("error in interpolated string: identifier or block expected",
                                                     skipIt = true)(EmptyTree)
      // Like Swiss cheese, with holes
      def stringCheese: Tree = atPos(in.offset) {
        val start        = in.offset
        val interpolator = in.name.encoded // ident() for INTERPOLATIONID

        val partsBuf = new ListBuffer[Tree]
        val exprsBuf = new ListBuffer[Tree]
        in.nextToken()
        while (in.token == STRINGPART) {
          partsBuf += literal()
          exprsBuf += (
            if (inPattern) dropAnyBraces(pattern())
            else in.token match {
              case IDENTIFIER => atPos(in.offset)(Ident(ident()))
              //case USCORE   => freshPlaceholder()  // ifonly etapolation
              case LBRACE     => expr()              // dropAnyBraces(expr0(Local))
              case THIS       => in.nextToken(); atPos(in.offset)(This(tpnme.EMPTY))
              case _          => errpolation()
            }
          )
        }
        if (in.token == STRINGLIT) partsBuf += literal()

      // Documenting that it is intentional that the ident is not rooted for purposes of virtualization
      //val t1 = atPos(o2p(start)) { Select(Select (Ident(nme.ROOTPKG), nme.scala_), nme.StringContextName) }
        val t1 = atPos(o2p(start)) { Ident(nme.StringContextName) }
        val t2 = atPos(start) { Apply(t1, partsBuf.toList) }
        t2 setPos t2.pos.makeTransparent
        val t3 = Select(t2, interpolator) setPos t2.pos
        atPos(start) { Apply(t3, exprsBuf.toList) }
      }
      if (inPattern) stringCheese
      else withPlaceholders(stringCheese, isAny = true) // string interpolator params are Any* by definition
    }

/* ------------- NEW LINES ------------------------------------------------- */

    def newLineOpt(): Unit = {
      if (in.token == NEWLINE) in.nextToken()
    }

    def newLinesOpt(): Unit = {
      if (in.token == NEWLINE || in.token == NEWLINES)
        in.nextToken()
    }

    def newLineOptWhenFollowedBy(token: Offset): Unit = {
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && in.next.token == token) newLineOpt()
    }

    def newLineOptWhenFollowing(p: Token => Boolean): Unit = {
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && p(in.next.token)) newLineOpt()
    }

/* ------------- TYPES ---------------------------------------------------- */

    /** {{{
     *  TypedOpt ::= [`:` Type]
     *  }}}
     */
    def typedOpt(): Tree =
      if (in.token == COLON) { in.nextToken(); typ() }
      else TypeTree()

    def typeOrInfixType(location: Location): Tree =
      if (location == Local) typ()
      else startInfixType()

    def annotTypeRest(t: Tree): Tree =
      annotations(skipNewLines = false).foldLeft(t)(makeAnnotated)

    /** {{{
     *  WildcardType ::= `_` TypeBounds
     *  }}}
     */
    def wildcardType(start: Offset) = {
      val pname = freshTypeName("_$")
      val t = atPos(start)(Ident(pname))
      val bounds = typeBounds()
      val param = atPos(t.pos union bounds.pos) { makeSyntheticTypeParam(pname, bounds) }
      placeholderTypes = param :: placeholderTypes
      t
    }

/* ----------- EXPRESSIONS ------------------------------------------------ */

    def condExpr(): Tree = {
      if (in.token == LPAREN) {
        in.nextToken()
        val r = expr()
        accept(RPAREN)
        if (isWildcard(r))
          placeholderParams.head.tpt match {
            case tpt @ TypeTree() => tpt.setType(definitions.BooleanTpe)
            case _                =>
          }
        r
      } else {
        accept(LPAREN)
        newLiteral(true)
      }
    }

    /* hook for IDE, unlike expression can be stubbed
     * don't use for any tree that can be inspected in the parser!
     */
    def statement(location: Location): Tree = expr(location) // !!! still needed?

    /** {{{
     *  Expr       ::= (Bindings | [`implicit`] Id | `_`)  `=>` Expr
     *               | Expr1
     *  ResultExpr ::= (Bindings | Id `:` CompoundType) `=>` Block
     *               | Expr1
     *  Expr1      ::= if `(` Expr `)` {nl} Expr [[semi] else Expr]
     *               | try (`{` Block `}` | Expr) [catch `{` CaseClauses `}`] [finally Expr]
     *               | while `(` Expr `)` {nl} Expr
     *               | do Expr [semi] while `(` Expr `)`
     *               | for (`(` Enumerators `)` | `{` Enumerators `}`) {nl} [yield] Expr
     *               | throw Expr
     *               | return [Expr]
     *               | [SimpleExpr `.`] Id `=` Expr
     *               | SimpleExpr1 ArgumentExprs `=` Expr
     *               | PostfixExpr Ascription
     *               | PostfixExpr match `{` CaseClauses `}`
     *  Bindings   ::= `(` [Binding {`,` Binding}] `)`
     *  Binding    ::= (Id | `_`) [`:` Type]
     *  Ascription ::= `:` CompoundType
     *               | `:` Annotation {Annotation}
     *               | `:` `_` `*`
     *  }}}
     */
    def expr(): Tree = expr(Local)

    def expr(location: Location): Tree = withPlaceholders(expr0(location), isAny = false)

    def expr0(location: Location): Tree = (in.token: @scala.annotation.switch) match {
      case IF =>
        def parseIf = atPos(in.skipToken()) {
          val cond = condExpr()
          newLinesOpt()
          val thenp = expr()
          val elsep = if (in.token == ELSE) { in.nextToken(); expr() }
          else literalUnit
          If(cond, thenp, elsep)
        }
        parseIf
      case TRY =>
        def parseTry = atPos(in.skipToken()) {
          val body = in.token match {
            case LBRACE => inBracesOrUnit(block())
            case LPAREN => inParensOrUnit(expr())
            case _ => expr()
          }
          def catchFromExpr() = List(makeCatchFromExpr(expr()))
          val catches: List[CaseDef] =
            if (in.token != CATCH) Nil
            else {
              in.nextToken()
              if (in.token != LBRACE) catchFromExpr()
              else inBracesOrNil {
                if (in.token == CASE) caseClauses()
                else catchFromExpr()
              }
            }
          val finalizer = in.token match {
            case FINALLY => in.nextToken(); expr()
            case _ => EmptyTree
          }
          Try(body, catches, finalizer)
        }
        parseTry
      case WHILE =>
        def parseWhile = {
          val start = in.offset
          atPos(in.skipToken()) {
            val cond = condExpr()
            newLinesOpt()
            val body = expr()
            makeWhile(start, cond, body)
          }
        }
        parseWhile
      case DO =>
        def parseDo = {
          atPos(in.skipToken()) {
            val lname: Name = freshTermName(nme.DO_WHILE_PREFIX)
            val body = expr()
            if (isStatSep) in.nextToken()
            accept(WHILE)
            val cond = condExpr()
            makeDoWhile(lname.toTermName, body, cond)
          }
        }
        parseDo
      case FOR =>
        val start = in.skipToken()
        def parseFor = atPos(start) {
          val enums =
            if (in.token == LBRACE) inBracesOrNil(enumerators())
            else inParensOrNil(enumerators())
          newLinesOpt()
          if (in.token == YIELD) {
            in.nextToken()
            gen.mkFor(enums, gen.Yield(expr()))
          } else {
            gen.mkFor(enums, expr())
          }
        }
        def adjustStart(tree: Tree) =
          if (tree.pos.isRange && start < tree.pos.start)
            tree setPos tree.pos.withStart(start)
          else tree
        adjustStart(parseFor)
      case RETURN =>
        def parseReturn =
          atPos(in.skipToken()) {
            Return(if (isExprIntro) expr() else literalUnit)
          }
        parseReturn
      case THROW =>
        def parseThrow =
          atPos(in.skipToken()) {
            Throw(expr())
          }
        parseThrow
      case IMPLICIT =>
        implicitClosure(in.skipToken(), location)
      case _ =>
        def parseOther = {
          var t = postfixExpr()
          if (in.token == EQUALS) {
            t match {
              case Ident(_) | Select(_, _) | Apply(_, _) =>
                t = atPos(t.pos.start, in.skipToken()) { gen.mkAssign(t, expr()) }
              case _ =>
            }
          } else if (in.token == COLON) {
            t = stripParens(t)
            val colonPos = in.skipToken()
            if (in.token == USCORE) {
              //todo: need to handle case where USCORE is a wildcard in a type
              val uscorePos = in.skipToken()
              if (isIdent && in.name == nme.STAR) {
                in.nextToken()
                t = atPos(t.pos.start, colonPos) {
                  Typed(t, atPos(uscorePos) { Ident(tpnme.WILDCARD_STAR) })
                }
              } else {
                syntaxErrorOrIncomplete("`*` expected", skipIt = true)
              }
            } else if (isAnnotation) {
              t = annotations(skipNewLines = false).foldLeft(t)(makeAnnotated)
            } else {
              t = atPos(t.pos.start, colonPos) {
                val tpt = typeOrInfixType(location)
                if (isWildcard(t))
                  (placeholderParams: @unchecked) match {
                    case (vd @ ValDef(mods, name, _, _)) :: rest =>
                      placeholderParams = treeCopy.ValDef(vd, mods, name, tpt.duplicate, EmptyTree) :: rest
                  }
                // this does not correspond to syntax, but is necessary to
                // accept closures. We might restrict closures to be between {...} only.
                Typed(t, tpt)
              }
            }
          } else if (in.token == MATCH) {
            t = atPos(t.pos.start, in.skipToken())(Match(stripParens(t), inBracesOrNil(caseClauses())))
          }
          // in order to allow anonymous functions as statements (as opposed to expressions) inside
          // templates, we have to disambiguate them from self type declarations - bug #1565
          // The case still missed is unparenthesized single argument, like "x: Int => x + 1", which
          // may be impossible to distinguish from a self-type and so remains an error.  (See #1564)
          def lhsIsTypedParamList() = t match {
            case Parens(xs) if xs.forall(isTypedParam) => true
            case _ => false
          }
          if (in.token == ARROW && (location != InTemplate || lhsIsTypedParamList)) {
            t = atPos(t.pos.start, in.skipToken()) {
              Function(convertToParams(t), if (location != InBlock) expr() else block())
            }
          }
          stripParens(t)
        }
        parseOther
    }

    def isTypedParam(t: Tree) = t.isInstanceOf[Typed]

    /** {{{
     *  Expr ::= implicit Id `=>` Expr
     *  }}}
     */

    def implicitClosure(start: Offset, location: Location): Tree = {
      val param0 = convertToParam {
        atPos(in.offset) {
          Ident(ident()) match {
            case expr if in.token == COLON  =>
              in.nextToken() ; Typed(expr, typeOrInfixType(location))
            case expr => expr
          }
        }
      }
      val param = copyValDef(param0)(mods = param0.mods | Flags.IMPLICIT)
      atPos(start, in.offset) {
        accept(ARROW)
        Function(List(param), if (location != InBlock) expr() else block())
      }
    }

    /** {{{
     *  PostfixExpr   ::= InfixExpr [Id [nl]]
     *  InfixExpr     ::= PrefixExpr
     *                  | InfixExpr Id [nl] InfixExpr
     *  }}}
     */
    def postfixExpr(): Tree = {
      val start = in.offset
      val base  = opstack

      @tailrec
      def loop(top: Tree): Tree = if (!isIdent) top else {
        pushOpInfo(reduceExprStack(base, top))
        newLineOptWhenFollowing(isExprIntroToken)
        if (isExprIntro)
          prefixExpr() match {
            case EmptyTree => reduceExprStack(base, top)
            case next      => loop(next)
          }
        else finishPostfixOp(start, base, popOpInfo())
      }

      reduceExprStack(base, loop(prefixExpr()))
    }

    /** {{{
     *  PrefixExpr   ::= [`-` | `+` | `~` | `!`] SimpleExpr
     *  }}}
     */
    def prefixExpr(): Tree = {
      if (isUnaryOp) {
        atPos(in.offset) {
          if (lookingAhead(isSimpleExprIntro)) {
            val namePos = in.offset
            val uname = nme.toUnaryName(rawIdent().toTermName)
            if (uname == nme.UNARY_- && isNumericLit)
              /* start at the -, not the number */
              simpleExprRest(literal(isNegated = true, start = namePos), canApply = true)
            else
              Select(stripParens(simpleExpr()), uname)
          }
          else simpleExpr()
        }
      }
      else simpleExpr()
    }
    def xmlLiteral(): Tree

    /** {{{
     *  SimpleExpr    ::= new (ClassTemplate | TemplateBody)
     *                  |  BlockExpr
     *                  |  SimpleExpr1 [`_`]
     *  SimpleExpr1   ::= literal
     *                  |  xLiteral
     *                  |  Path
     *                  |  `(` [Exprs] `)`
     *                  |  SimpleExpr `.` Id
     *                  |  SimpleExpr TypeArgs
     *                  |  SimpleExpr1 ArgumentExprs
     *  }}}
     */
    def simpleExpr(): Tree = {
      var canApply = true
      val t =
        if (isLiteral) literal()
        else in.token match {
          case XMLSTART =>
            xmlLiteral()
          case IDENTIFIER | BACKQUOTED_IDENT | THIS | SUPER =>
            path(thisOK = true, typeOK = false)
          case USCORE =>
            freshPlaceholder()
          case LPAREN =>
            atPos(in.offset)(makeParens(commaSeparated(expr())))
          case LBRACE =>
            canApply = false
            blockExpr()
          case NEW =>
            canApply = false
            val nstart = in.skipToken()
            val npos = r2p(nstart, nstart, in.lastOffset)
            val tstart = in.offset
            val (parents, self, stats) = template()
            val cpos = r2p(tstart, tstart, in.lastOffset max tstart)
            gen.mkNew(parents, self, stats, npos, cpos)
          case _ =>
            syntaxErrorOrIncompleteAnd("illegal start of simple expression", skipIt = true)(errorTermTree)
        }
      simpleExprRest(t, canApply = canApply)
    }

    @tailrec
    final def simpleExprRest(t: Tree, canApply: Boolean): Tree = {
      if (canApply) newLineOptWhenFollowedBy(LBRACE)
      in.token match {
        case DOT =>
          in.nextToken()
          simpleExprRest(selector(stripParens(t)), canApply = true)
        case LBRACKET =>
          val t1 = stripParens(t)
          t1 match {
            case Ident(_) | Select(_, _) | Apply(_, _) | Literal(_) =>
              var app: Tree = t1
              while (in.token == LBRACKET)
                app = atPos(app.pos.start, in.offset)(TypeApply(app, exprTypeArgs()))

              simpleExprRest(app, canApply = true)
            case _ =>
              t1
          }
        case LPAREN | LBRACE if (canApply) =>
          val app = atPos(t.pos.start, in.offset) {
            // look for anonymous function application like (f _)(x) and
            // translate to (f _).apply(x), bug #460
            val sel = t match {
              case Parens(List(Typed(_, _: Function))) =>
                Select(stripParens(t), nme.apply)
              case _ =>
                stripParens(t)
            }
            Apply(sel, argumentExprs())
          }
          simpleExprRest(app, canApply = true)
        case USCORE =>
          atPos(t.pos.start, in.skipToken()) { MethodValue(stripParens(t)) }
        case _ =>
          t
      }
    }

    /** {{{
     *  ArgumentExprs ::= `(` [Exprs] `)`
     *                  | [nl] BlockExpr
     *  }}}
     */
    def argumentExprs(): List[Tree] = {
      def args(): List[Tree] = commaSeparated(
        if (isIdent) treeInfo.assignmentToMaybeNamedArg(expr()) else expr()
      )
      in.token match {
        case LBRACE   => List(blockExpr())
        case LPAREN   => inParens(if (in.token == RPAREN) Nil else args())
        case _        => Nil
      }
    }
    /** A succession of argument lists. */
    def multipleArgumentExprs(): List[List[Tree]] = {
      if (in.token != LPAREN) Nil
      else argumentExprs() :: multipleArgumentExprs()
    }

    /** {{{
     *  BlockExpr ::= `{` (CaseClauses | Block) `}`
     *  }}}
     */
    def blockExpr(): Tree = atPos(in.offset) {
      inBraces {
        if (in.token == CASE) Match(EmptyTree, caseClauses())
        else block()
      }
    }

    /** {{{
     *  Block ::= BlockStatSeq
     *  }}}
     *  @note  Return tree does not carry position.
     */
    def block(): Tree = makeBlock(blockStatSeq())

    def caseClause(): CaseDef =
      atPos(in.offset)(makeCaseDef(pattern(), guard(), caseBlock()))

    /** {{{
     *  CaseClauses ::= CaseClause {CaseClause}
     *  CaseClause  ::= case Pattern [Guard] `=>` Block
     *  }}}
     */
    def caseClauses(): List[CaseDef] = {
      val cases = caseSeparated { caseClause() }
      if (cases.isEmpty)  // trigger error if there are no cases
        accept(CASE)

      cases
    }

    // IDE HOOK (so we can memoize case blocks) // needed?
    def caseBlock(): Tree =
      atPos(accept(ARROW))(block())

    /** {{{
     *  Guard ::= if PostfixExpr
     *  }}}
     */
    def guard(): Tree =
      if (in.token == IF) { in.nextToken(); stripParens(postfixExpr()) }
      else EmptyTree

    /** {{{
     *  Enumerators ::= Generator {semi Enumerator}
     *  Enumerator  ::=  Generator
     *                |  Guard
     *                |  Pattern1 `=` Expr
     *  }}}
     */
    def enumerators(): List[Tree] = {
      val enums = new ListBuffer[Tree]
      enums ++= enumerator(isFirst = true)
      while (isStatSep) {
        in.nextToken()
        enums ++= enumerator(isFirst = false)
      }
      enums.toList
    }

    def enumerator(isFirst: Boolean, allowNestedIf: Boolean = true): List[Tree] =
      if (in.token == IF && !isFirst) makeFilter(in.offset, guard()) :: Nil
      else generator(!isFirst, allowNestedIf)

    /** {{{
     *  Generator ::= Pattern1 (`<-` | `=`) Expr [Guard]
     *  }}}
     */
    def generator(eqOK: Boolean, allowNestedIf: Boolean = true): List[Tree] = {
      val start  = in.offset
      val hasVal = in.token == VAL
      if (hasVal)
        in.nextToken()

      val pat   = noSeq.pattern1()
      val point = in.offset
      val hasEq = in.token == EQUALS

      if (hasVal) {
        def msg(what: String, instead: String): String = s"`val` keyword in for comprehension is $what: $instead"
        if (hasEq) {
          val without = "instead, bind the value without `val`"
          if (currentRun.isScala214) syntaxError(in.offset, msg("unsupported", without))
          else deprecationWarning(in.offset, msg("deprecated", without), "2.10.0")
        }
        else syntaxError(in.offset, msg("unsupported", "just remove `val`"))
      }

      if (hasEq && eqOK) in.nextToken()
      else accept(LARROW)
      val rhs = expr()

      def loop(): List[Tree] =
        if (in.token != IF) Nil
        else makeFilter(in.offset, guard()) :: loop()

      val tail =
        if (allowNestedIf) loop()
        else Nil

      // why max? IDE stress tests have shown that lastOffset could be less than start,
      // I guess this happens if instead if a for-expression we sit on a closing paren.
      val genPos = r2p(start, point, in.lastOffset max start)
      gen.mkGenerator(genPos, pat, hasEq, rhs) :: tail
    }

    def makeFilter(start: Offset, tree: Tree) = gen.Filter(tree).setPos(r2p(start, tree.pos.point, tree.pos.end))

/* -------- PATTERNS ------------------------------------------- */

    /** Methods which implicitly propagate whether the initial call took
     *  place in a context where sequences are allowed.  Formerly, this
     *  was threaded through methods as boolean seqOK.
     *  @param isSequenceOK is a sequence pattern _* allowed?
     *  @param isXML are we in an XML pattern?
     */
    final class SeqContextSensitive(val isSequenceOK: Boolean, val isXML: Boolean) extends PatternContextSensitive {
      final def functionArgType(): Tree = argType()
      final def argType(): Tree = {
        val start = in.offset
        in.token match {
          case USCORE =>
            in.nextToken()
            if (in.token == SUBTYPE || in.token == SUPERTYPE) wildcardType(start)
            else atPos(start) { Bind(tpnme.WILDCARD, EmptyTree) }
          case _ =>
            typ() match {
              case Ident(name: TypeName) if nme.isVariableName(name) =>
                atPos(start) { Bind(name, EmptyTree) }
              case t => t
            }
        }
      }

      /** {{{
       *  Patterns ::= Pattern { `,` Pattern }
       *  SeqPatterns ::= SeqPattern { `,` SeqPattern }
       *  }}}
       */
      def patterns(): List[Tree] = commaSeparated(pattern())

      /** {{{
       *  Pattern  ::=  Pattern1 { `|` Pattern1 }
       *  SeqPattern ::= SeqPattern1 { `|` SeqPattern1 }
       *  }}}
       */
      def pattern(): Tree = {
        val start = in.offset
        def loop(): List[Tree] = pattern1() :: {
          if (isRawBar) { in.nextToken() ; loop() }
          else Nil
        }
        loop() match {
          case pat :: Nil => pat
          case xs         => atPos(start)(makeAlternative(xs))
        }
      }

      /** {{{
       *  Pattern1    ::= boundvarid `:` TypePat
       *                |  `_` `:` TypePat
       *                |  Pattern2
       *  SeqPattern1 ::= boundvarid `:` TypePat
       *                |  `_` `:` TypePat
       *                |  [SeqPattern2]
       *  }}}
       */
      def pattern1(): Tree = pattern2() match {
        case p @ Ident(name) if in.token == COLON =>
          if (nme.isVariableName(name)) {
            p.removeAttachment[BackquotedIdentifierAttachment.type]
            atPos(p.pos.start, in.skipToken())(Typed(p, compoundType()))
          } else {
            syntaxError(in.offset, "Pattern variables must start with a lower-case letter. (SLS 8.1.1.)")
            p
          }
        case p => p
      }

      /** {{{
       *  Pattern2    ::=  id  `@` Pattern3
       *                |  `_` `@` Pattern3
       *                |   Pattern3
       *  }}}
       */
      def pattern2(): Tree = (pattern3(), in.token) match {
        case (Ident(nme.WILDCARD), AT) =>
          in.nextToken()
          pattern3()
        case (p @ Ident(name), AT) =>
          in.nextToken()
          val body = pattern3()
          atPos(p.pos.start, p.pos.start, body.pos.end) {
            val t = Bind(name, body)
            body match {
              case Ident(nme.WILDCARD) if settings.warnUnusedPatVars => t updateAttachment NoWarnAttachment
              case _ => t
            }
          }
        case (p, _) => p
      }

      /** {{{
       *  Pattern3    ::= SimplePattern
       *                |  SimplePattern {Id [nl] SimplePattern}
       *  }}}
       */
      def pattern3(): Tree = {
        val top = simplePattern(() => badPattern3())
        val base = opstack
        // See scala/bug#3189, scala/bug#4832 for motivation. Cf scala/bug#3480 for counter-motivation.
        def isCloseDelim = in.token match {
          case RBRACE => isXML
          case RPAREN => !isXML
          case _      => false
        }
        def checkWildStar: Tree = top match {
          case Ident(nme.WILDCARD) if isSequenceOK && isRawStar => peekingAhead (
            if (isCloseDelim) atPos(top.pos.start, in.prev.offset)(Star(stripParens(top)))
            else EmptyTree
          )
          case _ => EmptyTree
        }
        @tailrec
        def loop(top: Tree): Tree = reducePatternStack(base, top) match {
          case next if isIdent && !isRawBar => pushOpInfo(next) ; loop(simplePattern(() => badPattern3()))
          case next                         => next
        }
        checkWildStar orElse stripParens(loop(top))
      }

      def badPattern3(): Tree = {
        def isComma                = in.token == COMMA
        def isDelimiter            = in.token == RPAREN || in.token == RBRACE
        def isCommaOrDelimiter     = isComma || isDelimiter
        val (isUnderscore, isStar) = opstack match {
          case OpInfo(Ident(nme.WILDCARD), nme.STAR, _, _) :: _ => (true,   true)
          case OpInfo(_, nme.STAR, _, _) :: _                   => (false,  true)
          case _                                                => (false, false)
        }
        def isSeqPatternClose = isUnderscore && isStar && isSequenceOK && isDelimiter
        val preamble = "bad simple pattern:"
        val subtext = (isUnderscore, isStar, isSequenceOK) match {
          case (true,  true, true)  if isComma            => "bad use of _* (a sequence pattern must be the last pattern)"
          case (true,  true, true)  if isDelimiter        => "bad brace or paren after _*"
          case (true,  true, false) if isDelimiter        => "bad use of _* (sequence pattern not allowed)"
          case (false, true, true)  if isDelimiter        => "use _* to match a sequence"
          case (false, true, _)     if isCommaOrDelimiter => "trailing * is not a valid pattern"
          case _                                          => null
        }
        val msg = if (subtext != null) s"$preamble $subtext" else "illegal start of simple pattern"
        // better recovery if don't skip delims of patterns
        val skip = !isCommaOrDelimiter || isSeqPatternClose
        syntaxErrorOrIncompleteAnd(msg, skip)(errorPatternTree)
      }

      /** {{{
       *  SimplePattern    ::= varid
       *                    |  `_`
       *                    |  literal
       *                    |  XmlPattern
       *                    |  StableId  /[TypeArgs]/ [`(` [Patterns] `)`]
       *                    |  StableId  [`(` [Patterns] `)`]
       *                    |  StableId  [`(` [Patterns] `,` [varid `@`] `_` `*` `)`]
       *                    |  `(` [Patterns] `)`
       *  }}}
       *
       * XXX: Hook for IDE
       */
      def simplePattern(): Tree = (
        // simple diagnostics for this entry point
        simplePattern(() => syntaxErrorOrIncompleteAnd("illegal start of simple pattern", skipIt = true)(errorPatternTree))
      )
      def simplePattern(onError: () => Tree): Tree = {
        val start = in.offset
        in.token match {
          case IDENTIFIER | BACKQUOTED_IDENT | THIS =>
            val t = stableId()
            in.token match {
              case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT =>
                t match {
                  case Ident(nme.MINUS) =>
                    return literal(isNegated = true, inPattern = true, start = start)
                  case _ =>
                }
              case _ =>
            }
            val typeAppliedTree = in.token match {
              case LBRACKET   => atPos(start, in.offset)(AppliedTypeTree(convertToTypeId(t), typeArgs()))
              case _          => t
            }
            in.token match {
              case LPAREN   => atPos(start, in.offset)(Apply(typeAppliedTree, argumentPatterns()))
              case _        => typeAppliedTree
            }
          case USCORE =>
            in.nextToken()
            atPos(start, start) { Ident(nme.WILDCARD) }
          case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT |
               STRINGLIT | INTERPOLATIONID | SYMBOLLIT | TRUE | FALSE | NULL =>
            literal(inPattern = true)
          case LPAREN =>
            atPos(start)(makeParens(noSeq.patterns()))
          case XMLSTART =>
            xmlLiteralPattern()
          case _ =>
            onError()
        }
      }
    }
    /** The implementation of the context sensitive methods for parsing outside of patterns. */
    final val outPattern = new PatternContextSensitive {
      def argType(): Tree = typ()
      def functionArgType(): Tree = paramType(repeatedParameterOK = false, useStartAsPosition = true)
    }
    /** The implementation for parsing inside of patterns at points where sequences are allowed. */
    final val seqOK = new SeqContextSensitive(isSequenceOK = true, isXML = false)

    /** The implementation for parsing inside of patterns at points where sequences are disallowed. */
    final val noSeq = new SeqContextSensitive(isSequenceOK = false, isXML = false)

    /** For use from xml pattern, where sequence is allowed and encouraged. */
    final val xmlSeqOK = new SeqContextSensitive(isSequenceOK = true, isXML = true)

    /** These are default entry points into the pattern context sensitive methods:
     *  they are all initiated from non-pattern context.
     */
    def typ(): Tree      = outPattern.typ()
    def startInfixType() = outPattern.infixType(InfixMode.FirstOp)
    def startAnnotType() = outPattern.annotType()
    def exprTypeArgs()   = outPattern.typeArgs()
    def exprSimpleType() = outPattern.simpleType()

    /** Default entry points into some pattern contexts. */
    def pattern(): Tree = noSeq.pattern()
    def seqPatterns(): List[Tree] = seqOK.patterns()
    def xmlSeqPatterns(): List[Tree] = xmlSeqOK.patterns() // Called from xml parser
    def argumentPatterns(): List[Tree] = inParens {
      if (in.token == RPAREN) Nil
      else seqPatterns()
    }
    def xmlLiteralPattern(): Tree

/* -------- MODIFIERS and ANNOTATIONS ------------------------------------------- */

    /** Drop `private` modifier when followed by a qualifier.
     *  Contract `abstract` and `override` to ABSOVERRIDE
     */
    @tailrec
    private def normalizeModifiers(mods: Modifiers): Modifiers =
      if (mods.isPrivate && mods.hasAccessBoundary)
        normalizeModifiers(mods &~ Flags.PRIVATE)
      else if (mods hasAllFlags (Flags.ABSTRACT | Flags.OVERRIDE))
        normalizeModifiers(mods &~ (Flags.ABSTRACT | Flags.OVERRIDE) | Flags.ABSOVERRIDE)
      else
        mods

    private def addMod(mods: Modifiers, mod: Long, pos: Position): Modifiers = {
      if (mods hasFlag mod) syntaxError(in.offset, "repeated modifier", skipIt = false)
      in.nextToken()
      (mods | mod) withPosition (mod, pos)
    }

    private def tokenRange(token: TokenData) =
      r2p(token.offset, token.offset, token.offset + token.name.length - 1)

    /** {{{
     *  AccessQualifier ::= `[` (Id | this) `]`
     *  }}}
     */
    def accessQualifierOpt(mods: Modifiers): Modifiers = {
      var result = mods
      if (in.token == LBRACKET) {
        in.nextToken()
        if (mods.hasAccessBoundary)
          syntaxError("duplicate private/protected qualifier", skipIt = false)
        result = if (in.token == THIS) { in.nextToken(); mods | Flags.LOCAL }
                 else Modifiers(mods.flags, identForType())
        accept(RBRACKET)
      }
      result
    }

    private val flagTokens: Map[Int, Long] = Map(
      ABSTRACT  -> Flags.ABSTRACT,
      FINAL     -> Flags.FINAL,
      IMPLICIT  -> Flags.IMPLICIT,
      LAZY      -> Flags.LAZY,
      OVERRIDE  -> Flags.OVERRIDE,
      PRIVATE   -> Flags.PRIVATE,
      PROTECTED -> Flags.PROTECTED,
      SEALED    -> Flags.SEALED
    )

    /** {{{
     *  AccessModifier ::= (private | protected) [AccessQualifier]
     *  }}}
     */
    def accessModifierOpt(): Modifiers = normalizeModifiers {
      in.token match {
        case m @ (PRIVATE | PROTECTED)  => in.nextToken() ; accessQualifierOpt(Modifiers(flagTokens(m)))
        case _                          => NoMods
      }
    }

    /** {{{
     *  Modifiers ::= {Modifier}
     *  Modifier  ::= LocalModifier
     *              |  AccessModifier
     *              |  override
     *  }}}
     */
    def modifiers(): Modifiers = normalizeModifiers {
      @tailrec
      def loop(mods: Modifiers): Modifiers = in.token match {
        case PRIVATE | PROTECTED =>
          loop(accessQualifierOpt(addMod(mods, flagTokens(in.token), tokenRange(in))))
        case ABSTRACT | FINAL | SEALED | OVERRIDE | IMPLICIT | LAZY =>
          loop(addMod(mods, flagTokens(in.token), tokenRange(in)))
        case NEWLINE =>
          in.nextToken()
          loop(mods)
        case _ =>
          mods
      }
      loop(NoMods)
    }

    /** {{{
     *  LocalModifiers ::= {LocalModifier}
     *  LocalModifier  ::= abstract | final | sealed | implicit | lazy
     *  }}}
     */
    def localModifiers(): Modifiers = {
      @tailrec
      def loop(mods: Modifiers): Modifiers =
        if (isLocalModifier) loop(addMod(mods, flagTokens(in.token), tokenRange(in)))
        else mods

      loop(NoMods)
    }

    /** {{{
     *  Annotations       ::= {`@` SimpleType {ArgumentExprs}}
     *  ConstrAnnotations ::= {`@` SimpleType ArgumentExprs}
     *  }}}
     */
    def annotations(skipNewLines: Boolean): List[Tree] = readAnnots {
      val t = annotationExpr()
      if (skipNewLines) newLineOpt()
      t
    }
    def constructorAnnotations(): List[Tree] = readAnnots {
      atPos(in.offset)(New(exprSimpleType(), List(argumentExprs())))
    }

    def annotationExpr(): Tree = atPos(in.offset) {
      val t = exprSimpleType()
      if (in.token == LPAREN) New(t, multipleArgumentExprs())
      else New(t, Nil)
    }

/* -------- PARAMETERS ------------------------------------------- */

    /** {{{
     *  ParamClauses      ::= {ParamClause} [[nl] `(` implicit Params `)`]
     *  ParamClause       ::= [nl] `(` [Params] `)`
     *  Params            ::= Param {`,` Param}
     *  Param             ::= {Annotation} Id [`:` ParamType] [`=` Expr]
     *  ClassParamClauses ::= {ClassParamClause} [[nl] `(` implicit ClassParams `)`]
     *  ClassParamClause  ::= [nl] `(` [ClassParams] `)`
     *  ClassParams       ::= ClassParam {`,` ClassParam}
     *  ClassParam        ::= {Annotation}  [{Modifier} (`val` | `var`)] Id [`:` ParamType] [`=` Expr]
     *  }}}
     */
    def paramClauses(owner: Name, contextBounds: List[Tree], ofCaseClass: Boolean): List[List[ValDef]] = {
      var implicitSection = -1
      var implicitOffset  = -1
      var warnAt          = -1
      var caseParam       = ofCaseClass
      val vds   = new ListBuffer[List[ValDef]]
      val start = in.offset
      def paramClause(): List[ValDef] = if (in.token == RPAREN) Nil else {
        val implicitmod =
          if (in.token == IMPLICIT) {
            if (implicitOffset == -1) { implicitOffset = in.offset ; implicitSection = vds.length }
            else if (warnAt == -1) warnAt = in.offset
            in.nextToken()
            Flags.IMPLICIT
          } else 0
        commaSeparated(param(owner, implicitmod, caseParam))
      }
      newLineOptWhenFollowedBy(LPAREN)
      while (in.token == LPAREN) {
        in.nextToken()
        vds += paramClause()
        accept(RPAREN)
        caseParam = false
        newLineOptWhenFollowedBy(LPAREN)
      }
      if (ofCaseClass) {
        def name = {
          val s = owner.decodedName.toString
          if (s != nme.ERROR.decodedName.toString) s else "C"
        }
        def elliptical = vds.map(_ => "(...)").mkString
        if (vds.isEmpty)
          syntaxError(start, s"case classes must have a parameter list; try 'case class $name()' or 'case object $name'")
        else if (vds.head.nonEmpty && vds.head.head.mods.isImplicit) {
          if (currentRun.isScala213)
            syntaxError(start, s"case classes must have a non-implicit parameter list; try 'case class $name()$elliptical'")
          else {
            deprecationWarning(start, s"case classes should have a non-implicit parameter list; adapting to 'case class $name()$elliptical'", "2.12.2")
            vds.insert(0, List.empty[ValDef])
            vds(1) = vds(1).map(vd => copyValDef(vd)(mods = vd.mods & ~Flags.CASEACCESSOR))
            if (implicitSection != -1) implicitSection += 1
          }
        }
      }
      if (implicitSection != -1 && implicitSection != vds.length - 1)
        syntaxError(implicitOffset, "an implicit parameter section must be last")
      if (warnAt != -1)
        syntaxError(warnAt, "multiple implicit parameter sections are not allowed")
      else if (settings.warnExtraImplicit) {
        // guard against anomalous class C(private implicit val x: Int)(implicit s: String)
        val ttl = vds.count { case ValDef(mods, _, _, _) :: _ => mods.isImplicit ; case _ => false }
        if (ttl > 1)
          warning(in.offset, s"$ttl parameter sections are effectively implicit")
      }
      val result = vds.toList
      if (owner == nme.CONSTRUCTOR && (result.isEmpty || (result.head take 1 exists (_.mods.isImplicit)))) {
        in.token match {
          case LBRACKET   => syntaxError(in.offset, "no type parameters allowed here", skipIt = false)
          case EOF        => incompleteInputError("auxiliary constructor needs non-implicit parameter list")
          case _          => syntaxError(start, "auxiliary constructor needs non-implicit parameter list", skipIt = false)
        }
      }
      addEvidenceParams(owner, result, contextBounds)
    }

    /** {{{
     *  ParamType ::= Type | `=>` Type | Type `*`
     *  }}}
     */
    def paramType(): Tree = paramType(repeatedParameterOK = true, useStartAsPosition = false)
    def paramType(repeatedParameterOK: Boolean, useStartAsPosition: Boolean): Tree = {
      val start = in.offset
      in.token match {
        case ARROW  =>
          in.nextToken()
          atPos(start)(byNameApplication(typ()))
        case _      =>
          val t = typ()
          if (isRawStar) {
            in.nextToken()
            if (!repeatedParameterOK) { syntaxError("repeated parameters are only allowed in method signatures; use Seq instead", skipIt = false) ; t }
            else if (useStartAsPosition) atPos(start)(repeatedApplication(t))
            else atPos(t.pos.start, t.pos.point)(repeatedApplication(t))
          }
          else t
      }
    }

    def param(owner: Name, implicitmod: Long, caseParam: Boolean): ValDef = {
      val start = in.offset
      val annots = annotations(skipNewLines = false)
      var mods = Modifiers(Flags.PARAM)
      if (owner.isTypeName) {
        mods = modifiers() | Flags.PARAMACCESSOR
        if (mods.isLazy) syntaxError("lazy modifier not allowed here. Use call-by-name parameters instead", skipIt = false)
        in.token match {
          case v @ (VAL | VAR) =>
            mods = mods withPosition (in.token.toLong, tokenRange(in))
            if (v == VAR) mods |= Flags.MUTABLE
            in.nextToken()
          case _ =>
            if (mods.flags != Flags.PARAMACCESSOR) accept(VAL)
            if (!caseParam) mods |= Flags.PrivateLocal
        }
        if (caseParam) mods |= Flags.CASEACCESSOR
      }
      val nameOffset = in.offset
      val name = ident()
      var bynamemod = 0L
      val tpt = {
          accept(COLON)
          if (in.token == ARROW) {
            if (owner.isTypeName && !mods.isLocalToThis)
              syntaxError(
                in.offset,
                (if (mods.isMutable) "`var`" else "`val`") +
                " parameters may not be call-by-name", skipIt = false)
            else bynamemod = Flags.BYNAMEPARAM
          }
          paramType()
        }
      val default =
        if (in.token == EQUALS) {
          in.nextToken()
          mods |= Flags.DEFAULTPARAM
          expr()
        } else EmptyTree
      atPos(start, if (name == nme.ERROR) start else nameOffset) {
        ValDef((mods | implicitmod | bynamemod) withAnnotations annots, name.toTermName, tpt, default)
      }
    }

    /** {{{
     *  TypeParamClauseOpt    ::= [TypeParamClause]
     *  TypeParamClause       ::= `[` VariantTypeParam {`,` VariantTypeParam} `]`]
     *  VariantTypeParam      ::= {Annotation} [`+` | `-`] TypeParam
     *  FunTypeParamClauseOpt ::= [FunTypeParamClause]
     *  FunTypeParamClause    ::= `[` TypeParam {`,` TypeParam} `]`]
     *  TypeParam             ::= Id TypeParamClauseOpt TypeBounds {<% Type} {":" Type}
     *  }}}
     */
    def typeParamClauseOpt(owner: Name, contextBoundBuf: ListBuffer[Tree]): List[TypeDef] = {
      def typeParam(ms: Modifiers): TypeDef = {
        var mods = ms | Flags.PARAM
        val start = in.offset
        if (owner.isTypeName && isIdent) {
          if (in.name == raw.PLUS) {
            in.nextToken()
            mods |= Flags.COVARIANT
          } else if (in.name == raw.MINUS) {
            in.nextToken()
            mods |= Flags.CONTRAVARIANT
          }
        }
        val nameOffset = in.offset
        // TODO AM: freshTermName(o2p(in.skipToken()), "_$$"), will need to update test suite
        val pname: TypeName = wildcardOrIdent().toTypeName
        val param = atPos(start, nameOffset) {
          val tparams = typeParamClauseOpt(pname, null) // @M TODO null --> no higher-order context bounds for now
          TypeDef(mods, pname, tparams, typeBounds())
        }
        if (contextBoundBuf ne null) {
          def msg(what: String) = s"""view bounds are $what; use an implicit parameter instead.
                                     |  example: instead of `def f[A <% Int](a: A)` use `def f[A](a: A)(implicit ev: A => Int)`""".stripMargin
          while (in.token == VIEWBOUND) {
            if (currentRun.isScala214) syntaxError(in.offset, msg("unsupported"))
            else deprecationWarning(in.offset, msg("deprecated"), "2.12.0")
            contextBoundBuf += atPos(in.skipToken())(makeFunctionTypeTree(List(Ident(pname)), typ()))
          }
          while (in.token == COLON) {
            contextBoundBuf += atPos(in.skipToken()) {
              AppliedTypeTree(typ(), List(Ident(pname)))
            }
          }
        }
        param
      }
      newLineOptWhenFollowedBy(LBRACKET)
      if (in.token == LBRACKET) inBrackets(commaSeparated(typeParam(NoMods withAnnotations annotations(skipNewLines = true))))
      else Nil
    }

    /** {{{
     *  TypeBounds ::= [`>:` Type] [`<:` Type]
     *  }}}
     */
    def typeBounds(): TypeBoundsTree = {
      val t = checkNoEscapingPlaceholders {
        val lo = bound(SUPERTYPE)
        val hi = bound(SUBTYPE)
        TypeBoundsTree(lo, hi)
      }
      val defined = List(t.hi, t.lo) filter (_.pos.isDefined)

      if (defined.nonEmpty)
        t setPos wrappingPos(defined)
      else
        t setPos o2p(in.offset)
    }

    def bound(tok: Token): Tree = if (in.token == tok) { in.nextToken(); typ() } else EmptyTree

/* -------- DEFS ------------------------------------------- */


    /** {{{
     *  Import  ::= import ImportExpr {`,` ImportExpr}
     *  }}}
     */
    def importClause(): List[Tree] = {
      val offset = accept(IMPORT)
      commaSeparated(importExpr()) match {
        case Nil => Nil
        case t :: rest =>
          // The first import should start at the position of the keyword.
          t.setPos(t.pos.withStart(offset))
          t :: rest
      }
    }

    /** {{{
     *  ImportExpr ::= StableId `.` (Id | `_` | ImportSelectors)
     *  }}}
     */
    def importExpr(): Tree = {
      val start = in.offset
      def thisDotted(name: TypeName) = {
        in.nextToken()
        val t = atPos(start)(This(name))
        accept(DOT)
        val result = selector(t)
        accept(DOT)
        result
      }
      /* Walks down import `foo.bar.baz.{ ... }` until it ends at
       * an underscore, a left brace, or an undotted identifier.
       */
      def loop(expr: Tree): Tree = {
        expr setPos expr.pos.makeTransparent
        val selectors: List[ImportSelector] = in.token match {
          case USCORE   => List(wildImportSelector()) // import foo.bar._;
          case LBRACE   => importSelectors()      // import foo.bar.{ x, y, z }
          case _        =>
            val nameOffset = in.offset
            val name = ident()
            if (in.token == DOT) {
              // import foo.bar.ident.<unknown> and so create a select node and recurse.
              val t = atPos(start, if (name == nme.ERROR) in.offset else nameOffset)(Select(expr, name))
              in.nextToken()
              return loop(t)
            }
            // import foo.bar.Baz;
            else List(makeImportSelector(name, nameOffset))
        }
        // reaching here means we're done walking.
        atPos(start)(Import(expr, selectors))
      }

      loop(in.token match {
        case THIS   => thisDotted(tpnme.EMPTY)
        case _      =>
          val id = atPos(start)(Ident(ident()))

          if (in.token == DOT || !isStatSep) accept(DOT)
          else syntaxError(in.lastOffset, s". expected", skipIt = false)

          if (in.token == THIS) thisDotted(id.name.toTypeName)
          else id
      })
    }

    /** {{{
     *  ImportSelectors ::= `{` {ImportSelector `,`} (ImportSelector | `_`) `}`
     *  }}}
     */
    def importSelectors(): List[ImportSelector] = {
      val selectors = inBracesOrNil(commaSeparated(importSelector()))
      for (t <- selectors.init if t.isWildcard) syntaxError(t.namePos, "Wildcard import must be in last position")
      selectors
    }

    def wildcardOrIdent() = {
      if (in.token == USCORE) { in.nextToken() ; nme.WILDCARD }
      else ident()
    }

    /** {{{
     *  ImportSelector ::= Id [`=>` Id | `=>` `_`]
     *  }}}
     */
    def importSelector(): ImportSelector = {
      val start        = in.offset
      val bbq          = in.token == BACKQUOTED_IDENT
      val name         = wildcardOrIdent()
      var renameOffset = -1
      val rename       = in.token match {
        case ARROW =>
          in.nextToken()
          renameOffset = in.offset
          if (name == nme.WILDCARD && !bbq) syntaxError(renameOffset, "Wildcard import cannot be renamed")
          wildcardOrIdent()
        case _ if name == nme.WILDCARD && !bbq => null
        case _     =>
          renameOffset = start
          name
      }
      ImportSelector(name, start, rename, renameOffset)
    }

    def wildImportSelector(): ImportSelector = {
      val selector = ImportSelector.wildAt(in.offset)
      in.nextToken()
      selector
    }

    /** {{{
     *  Def    ::= val PatDef
     *           | var PatDef
     *           | def FunDef
     *           | type [nl] TypeDef
     *           | TmplDef
     *  Dcl    ::= val PatDcl
     *           | var PatDcl
     *           | def FunDcl
     *           | type [nl] TypeDcl
     *  }}}
     */
    def defOrDcl(pos: Offset, mods: Modifiers): List[Tree] = {
      if (mods.isLazy && in.token != VAL)
        syntaxError("lazy not allowed here. Only vals can be lazy", skipIt = false)
      in.token match {
        case VAL =>
          patDefOrDcl(pos, mods withPosition(VAL, tokenRange(in)))
        case VAR =>
          patDefOrDcl(pos, (mods | Flags.MUTABLE) withPosition (VAR, tokenRange(in)))
        case DEF =>
          List(funDefOrDcl(pos, mods withPosition(DEF, tokenRange(in))))
        case TYPE =>
          List(typeDefOrDcl(pos, mods withPosition(TYPE, tokenRange(in))))
        case _ =>
          List(tmplDef(pos, mods))
      }
    }

    private def caseAwareTokenOffset = if (in.token == CASECLASS || in.token == CASEOBJECT) in.prev.offset else in.offset

    def nonLocalDefOrDcl : List[Tree] = {
      val annots = annotations(skipNewLines = true)
      defOrDcl(caseAwareTokenOffset, modifiers() withAnnotations annots)
    }

    /** {{{
     *  PatDef ::= Pattern2 {`,` Pattern2} [`:` Type] `=` Expr
     *  ValDcl ::= Id {`,` Id} `:` Type
     *  VarDef ::= PatDef | Id {`,` Id} `:` Type `=` `_`
     *  }}}
     */
    def patDefOrDcl(pos : Int, mods: Modifiers): List[Tree] = {
      var newmods = mods
      in.nextToken()
      val lhs = commaSeparated(stripParens(noSeq.pattern2()))
      val tp = typedOpt()
      val rhs =
        if (!tp.isEmpty && in.token != EQUALS) {
          newmods = newmods | Flags.DEFERRED
          EmptyTree
        } else {
          accept(EQUALS)
          expr() match {
            case x if !tp.isEmpty && newmods.isMutable && lhs.forall(_.isInstanceOf[Ident]) && isWildcard(x) =>
              tp match {
                case SingletonTypeTree(Literal(Constant(_))) =>
                  syntaxError(tp.pos, "default initialization prohibited for literal-typed vars", skipIt = false)
                case _ =>
              }
              placeholderParams = placeholderParams.tail
              newmods = newmods | Flags.DEFAULTINIT
              EmptyTree
            case x => x
          }
        }
      def mkDefs(p: Tree, tp: Tree, rhs: Tree): List[Tree] = {
        val trees = {
          val pat = if (tp.isEmpty) p else Typed(p, tp) setPos (p.pos union tp.pos)
          makePatDef(newmods, pat, rhs)
        }
        if (newmods.isDeferred) {
          trees match {
            case List(ValDef(_, _, _, EmptyTree)) =>
              if (mods.isLazy) syntaxError(p.pos, "lazy values may not be abstract", skipIt = false)
            case _ => syntaxError(p.pos, "pattern definition may not be abstract", skipIt = false)
          }
        }
        trees
      }
      val trees = (lhs.toList.init flatMap (mkDefs(_, tp.duplicate, rhs.duplicate))) ::: mkDefs(lhs.last, tp, rhs)
      val hd = trees.head
      hd setPos hd.pos.withStart(pos)
      ensureNonOverlapping(hd, trees.tail)
      trees
    }

    /** {{{
     *  VarDef ::= PatDef
     *           | Id {`,` Id} `:` Type `=` `_`
     *  VarDcl ::= Id {`,` Id} `:` Type
     *  }}}
    def varDefOrDcl(mods: Modifiers): List[Tree] = {
      var newmods = mods | Flags.MUTABLE
      val lhs = new ListBuffer[(Int, Name)]
      do {
        in.nextToken()
        lhs += (in.offset, ident())
      } while (in.token == COMMA)
      val tp = typedOpt()
      val rhs = if (tp.isEmpty || in.token == EQUALS) {
        accept(EQUALS)
        if (!tp.isEmpty && in.token == USCORE) {
          in.nextToken()
          EmptyTree
        } else {
          expr()
        }
      } else {
        newmods = newmods | Flags.DEFERRED
        EmptyTree
      }
    }
     */

    /** {{{
     *  FunDef ::= FunSig [`:` Type] `=` [`macro`] Expr
     *          |  FunSig [nl] `{` Block `}`
     *          |  `this` ParamClause ParamClauses
     *                 (`=` ConstrExpr | [nl] ConstrBlock)
     *  FunDcl ::= FunSig [`:` Type]
     *  FunSig ::= id [FunTypeParamClause] ParamClauses
     *  }}}
     */
    def funDefOrDcl(start : Int, mods: Modifiers): Tree = {
      in.nextToken()
      if (in.token == THIS) {
        atPos(start, in.skipToken()) {
          val vparamss = paramClauses(nme.CONSTRUCTOR, classContextBounds map (_.duplicate), ofCaseClass = false)
          newLineOptWhenFollowedBy(LBRACE)
          val rhs = in.token match {
            case LBRACE   => atPos(in.offset) { constrBlock(vparamss) }
            case _        => accept(EQUALS) ; atPos(in.offset) { constrExpr(vparamss) }
          }
          DefDef(mods, nme.CONSTRUCTOR, List(), vparamss, TypeTree(), rhs)
        }
      }
      else {
        val nameOffset = in.offset
        val name = identOrMacro()
        funDefRest(start, nameOffset, mods, name)
      }
    }

    def funDefRest(start: Offset, nameOffset: Offset, mods: Modifiers, name: Name): Tree = {
      val result = atPos(start, if (name.toTermName == nme.ERROR) start else nameOffset) {
        var newmods = mods
        // contextBoundBuf is for context bounded type parameters of the form
        // [T : B] or [T : => B]; it contains the equivalent implicit parameter type,
        // i.e. (B[T] or T => B)
        val contextBoundBuf = new ListBuffer[Tree]
        val tparams = typeParamClauseOpt(name, contextBoundBuf)
        val vparamss = paramClauses(name, contextBoundBuf.toList, ofCaseClass = false)
        newLineOptWhenFollowedBy(LBRACE)
        var restype = fromWithinReturnType(typedOpt())
        def msg(what: String, instead: String) =
          s"procedure syntax is $what: instead, add `$instead` to explicitly declare `$name`'s return type"
        val rhs =
          if (isStatSep || in.token == RBRACE) {
            if (restype.isEmpty) {
              if (currentRun.isScala214) syntaxError(in.lastOffset, msg("unsupported", ": Unit"))
              else deprecationWarning(in.lastOffset, msg("deprecated", ": Unit"), "2.13.0")
              restype = scalaUnitConstr
            }
            newmods |= Flags.DEFERRED
            EmptyTree
          } else if (restype.isEmpty && in.token == LBRACE) {
            if (currentRun.isScala214) syntaxError(in.offset, msg("unsupported", ": Unit ="))
            else deprecationWarning(in.offset, msg("deprecated", ": Unit ="), "2.13.0")
            restype = scalaUnitConstr
            blockExpr()
          } else {
            if (in.token == EQUALS) {
              in.nextTokenAllow(nme.MACROkw)
              if (isMacro) {
                in.nextToken()
                newmods |= Flags.MACRO
              }
            } else {
              accept(EQUALS)
            }
            expr()
          }
        DefDef(newmods, name.toTermName, tparams, vparamss, restype, rhs)
      }
      signalParseProgress(result.pos)
      result
    }

    /** {{{
     *  ConstrExpr      ::=  SelfInvocation
     *                    |  ConstrBlock
     *  }}}
     */
    def constrExpr(vparamss: List[List[ValDef]]): Tree =
      if (in.token == LBRACE) constrBlock(vparamss)
      else Block(selfInvocation(vparamss) :: Nil, literalUnit)

    /** {{{
     *  SelfInvocation  ::= this ArgumentExprs {ArgumentExprs}
     *  }}}
     */
    def selfInvocation(vparamss: List[List[ValDef]]): Tree =
      atPos(accept(THIS)) {
        newLineOptWhenFollowedBy(LBRACE)
        var t = Apply(Ident(nme.CONSTRUCTOR), argumentExprs())
        newLineOptWhenFollowedBy(LBRACE)
        while (in.token == LPAREN || in.token == LBRACE) {
          t = Apply(t, argumentExprs())
          newLineOptWhenFollowedBy(LBRACE)
        }
        if (classContextBounds.isEmpty) t
        else Apply(t, vparamss.last.map(vp => Ident(vp.name)))
      }

    /** {{{
     *  ConstrBlock    ::=  `{` SelfInvocation {semi BlockStat} `}`
     *  }}}
     */
    def constrBlock(vparamss: List[List[ValDef]]): Tree =
      atPos(in.skipToken()) {
        val stats = selfInvocation(vparamss) :: {
          if (isStatSep) { in.nextToken(); blockStatSeq() }
          else Nil
        }
        accept(RBRACE)
        Block(stats, literalUnit)
      }

    /** {{{
     *  TypeDef ::= type Id [TypeParamClause] `=` Type
     *            | FunSig `=` Expr
     *  TypeDcl ::= type Id [TypeParamClause] TypeBounds
     *  }}}
     */
    def typeDefOrDcl(start: Offset, mods: Modifiers): Tree = {
      in.nextToken()
      newLinesOpt()
      atPos(start, in.offset) {
        val name = identForType()
        // @M! a type alias as well as an abstract type may declare type parameters
        val tparams = typeParamClauseOpt(name, null)
        in.token match {
          case EQUALS =>
            in.nextToken()
            TypeDef(mods, name, tparams, typ())
          case t if t == SUPERTYPE || t == SUBTYPE || t == COMMA || t == RBRACE || isStatSep(t) =>
            TypeDef(mods | Flags.DEFERRED, name, tparams, typeBounds())
          case _ =>
            syntaxErrorOrIncompleteAnd("`=`, `>:`, or `<:` expected", skipIt = true)(
              // assume a dummy type def so as to have somewhere to stash the annotations
              TypeDef(mods, tpnme.ERROR, Nil, EmptyTree)
            )
        }
      }
    }

    /** Hook for IDE, for top-level classes/objects. */
    def topLevelTmplDef: Tree = {
      val annots = annotations(skipNewLines = true)
      val pos    = caseAwareTokenOffset
      val mods   = modifiers() withAnnotations annots
      tmplDef(pos, mods)
    }

    /** {{{
     *  TmplDef ::= [case] class ClassDef
     *            |  [case] object ObjectDef
     *            |  [override] trait TraitDef
     *  }}}
     */
    def tmplDef(pos: Offset, mods: Modifiers): Tree = {
      if (mods.isLazy) syntaxError("classes cannot be lazy", skipIt = false)
      in.token match {
        case TRAIT =>
          classDef(pos, (mods | Flags.TRAIT | Flags.ABSTRACT) withPosition (Flags.TRAIT, tokenRange(in)))
        case CLASS =>
          classDef(pos, mods)
        case CASECLASS =>
          classDef(pos, (mods | Flags.CASE) withPosition (Flags.CASE, tokenRange(in.prev /*scanner skips on 'case' to 'class', thus take prev*/)))
        case OBJECT =>
          objectDef(pos, mods)
        case CASEOBJECT =>
          objectDef(pos, (mods | Flags.CASE) withPosition (Flags.CASE, tokenRange(in.prev /*scanner skips on 'case' to 'object', thus take prev*/)))
        case _ =>
          syntaxErrorOrIncompleteAnd("expected start of definition", skipIt = true)(
            // assume a class definition so as to have somewhere to stash the annotations
            atPos(pos)(gen.mkClassDef(mods, tpnme.ERROR, Nil, Template(Nil, noSelfType, Nil)))
          )
      }
    }

    /** {{{
     *  ClassDef ::= Id [TypeParamClause] ConstrAnnotations
     *               [AccessModifier] ClassParamClauses RequiresTypeOpt ClassTemplateOpt
     *  TraitDef ::= Id [TypeParamClause] RequiresTypeOpt TraitTemplateOpt
     *  }}}
     */
    def classDef(start: Offset, mods: Modifiers): ClassDef = {
      in.nextToken()
      val nameOffset = in.offset
      val name = identForType()
      atPos(start, if (name == tpnme.ERROR) start else nameOffset) {
        savingClassContextBounds {
          val contextBoundBuf = new ListBuffer[Tree]
          val tparams = typeParamClauseOpt(name, contextBoundBuf)
          classContextBounds = contextBoundBuf.toList
          val tstart = (in.offset :: classContextBounds.map(_.pos.start)).min
          if (!classContextBounds.isEmpty && mods.isTrait) {
            val viewBoundsExist = if (currentRun.isScala214) "" else " nor view bounds `<% ...`"
              syntaxError(s"traits cannot have type parameters with context bounds `: ...`$viewBoundsExist", skipIt = false)
            classContextBounds = List()
          }
          val constrAnnots = if (!mods.isTrait) constructorAnnotations() else Nil
          val (constrMods, vparamss) =
            if (mods.isTrait) (Modifiers(Flags.TRAIT), List())
            else (accessModifierOpt(), paramClauses(name, classContextBounds, ofCaseClass = mods.isCase))
          val template = templateOpt(mods, name, constrMods withAnnotations constrAnnots, vparamss, tstart)
          val result = gen.mkClassDef(mods, name, tparams, template)
          // Context bounds generate implicit parameters (part of the template) with types
          // from tparams: we need to ensure these don't overlap
          if (!classContextBounds.isEmpty)
            ensureNonOverlapping(template, tparams)
          result
        }
      }
    }

    /** {{{
     *  ObjectDef       ::= Id ClassTemplateOpt
     *  }}}
     */
    def objectDef(start: Offset, mods: Modifiers, isPackageObject: Boolean = false): ModuleDef = {
      in.nextToken()
      val nameOffset = in.offset
      val name = ident()
      val tstart = in.offset
      atPos(start, if (name == nme.ERROR) start else nameOffset) {
        val template = templateOpt(mods, if (isPackageObject) nme.PACKAGEkw else name, NoMods, Nil, tstart)
        ModuleDef(mods, name.toTermName, template)
      }
    }

    /** Create a tree representing a package object, converting
     *  {{{
     *    package object foo { ... }
     *  }}}
     *  to
     *  {{{
     *    package foo {
     *      object `package` { ... }
     *    }
     *  }}}
     */
    def packageObjectDef(start: Offset): PackageDef = {
      val defn   = objectDef(in.offset, NoMods, isPackageObject = true)
      val pidPos = o2p(defn.pos.start)
      val pkgPos = r2p(start, pidPos.point)
      gen.mkPackageObject(defn, pidPos, pkgPos)
    }

    def packageOrPackageObject(start: Offset): Tree =
      if (in.token == OBJECT) joinComment(packageObjectDef(start) :: Nil).head
      else {
        in.flushDoc
        makePackaging(start, pkgQualId(), inBracesOrNil(topStatSeq()))
      }

    /** {{{
     *  ClassParents       ::= AnnotType {`(` [Exprs] `)`} {with AnnotType}
     *  TraitParents       ::= AnnotType {with AnnotType}
     *  }}}
     */
    def templateParents(): List[Tree] = {
      val parents = new ListBuffer[Tree]
      def readAppliedParent() = {
        val start = in.offset
        val parent = startAnnotType()
        parents += (in.token match {
          case LPAREN => atPos(start)(multipleArgumentExprs().foldLeft(parent)(Apply.apply))
          case _      => parent
        })
      }
      readAppliedParent()
      while (in.token == WITH) { in.nextToken(); readAppliedParent() }
      parents.toList
    }

    /** {{{
     *  ClassTemplate ::= [EarlyDefs with] ClassParents [TemplateBody]
     *  TraitTemplate ::= [EarlyDefs with] TraitParents [TemplateBody]
     *  EarlyDefs     ::= `{` [EarlyDef {semi EarlyDef}] `}`
     *  EarlyDef      ::= Annotations Modifiers PatDef
     *  }}}
     */
    def template(): (List[Tree], ValDef, List[Tree]) = {
      newLineOptWhenFollowedBy(LBRACE)
      if (in.token == LBRACE) {
        val braceOffset = in.offset
        // @S: pre template body cannot stub like post body can!
        val (self, body) = templateBody(isPre = true)
        if (in.token == WITH && (self eq noSelfType)) {
          val advice =
            if (currentRun.isScala214) "use trait parameters instead."
            else "they will be replaced by trait parameters in 2.14, see the migration guide on avoiding var/val in traits."
          deprecationWarning(braceOffset, s"early initializers are deprecated; $advice", "2.13.0")
          val earlyDefs: List[Tree] = body.map(ensureEarlyDef).filter(_.nonEmpty)
          in.nextToken()
          val parents = templateParents()
          val (self1, body1) = templateBodyOpt(parenMeansSyntaxError = false)
          (parents, self1, earlyDefs ::: body1)
        } else {
          (List(), self, body)
        }
      } else {
        val parents = templateParents()
        val (self, body) = templateBodyOpt(parenMeansSyntaxError = false)
        (parents, self, body)
      }
    }

    def ensureEarlyDef(tree: Tree): Tree = tree match {
      case vdef @ ValDef(mods, _, _, _) if !mods.isDeferred =>
        copyValDef(vdef)(mods = mods | Flags.PRESUPER)
      case tdef @ TypeDef(mods, name, tparams, rhs) =>
        def msg(what: String): String = s"early type members are $what: move them to the regular body; the semantics are the same"
        if (currentRun.isScala214) syntaxError(tdef.pos.point, msg("unsupported"))
        else deprecationWarning(tdef.pos.point, msg("deprecated"), "2.11.0")
        treeCopy.TypeDef(tdef, mods | Flags.PRESUPER, name, tparams, rhs)
      case docdef @ DocDef(comm, rhs) =>
        treeCopy.DocDef(docdef, comm, rhs)
      case stat if !stat.isEmpty =>
        syntaxError(stat.pos, "only concrete field definitions allowed in early object initialization section", skipIt = false)
        EmptyTree
      case _ =>
        EmptyTree
    }

    /** {{{
     *  ClassTemplateOpt ::= `extends` ClassTemplate | [[`extends`] TemplateBody]
     *  TraitTemplateOpt ::= TraitExtends TraitTemplate | [[TraitExtends] TemplateBody]
     *  TraitExtends     ::= `extends` | `<:` (deprecated)
     *  }}}
     */
    def templateOpt(mods: Modifiers, name: Name, constrMods: Modifiers, vparamss: List[List[ValDef]], tstart: Offset): Template = {
      def deprecatedUsage(): Boolean = {
        deprecationWarning(in.offset, "Using `<:` for `extends` is deprecated", since = "2.12.5")
        true
      }
      val (parents, self, body) = (
        if (in.token == EXTENDS || in.token == SUBTYPE && mods.isTrait && deprecatedUsage()) {
          in.nextToken()
          template()
        }
        else {
          newLineOptWhenFollowedBy(LBRACE)
          val (self, body) = templateBodyOpt(parenMeansSyntaxError = mods.isTrait || name.isTermName)
          (List(), self, body)
        }
      )
      def anyvalConstructor() = (
        // Not a well-formed constructor, has to be finished later - see note
        // regarding AnyVal constructor in AddInterfaces.
        DefDef(NoMods, nme.CONSTRUCTOR, Nil, ListOfNil, TypeTree(), Block(Nil, literalUnit))
      )
      val parentPos = o2p(in.offset)
      val tstart1 = if (body.isEmpty && in.lastOffset < tstart) in.lastOffset else tstart

      // we can't easily check this later, because `gen.mkParents` adds the default AnyRef parent, and we need to warn based on what the user wrote
      if (name == nme.PACKAGEkw && parents.nonEmpty && settings.isScala214)
        deprecationWarning(tstart, s"package object inheritance is deprecated (https://github.com/scala/scala-dev/issues/441);\n" +
                                   s"drop the `extends` clause or use a regular object instead", "2.14.0")

      atPos(tstart1) {
        // Exclude only the 9 primitives plus AnyVal.
        if (inScalaRootPackage && ScalaValueClassNames.contains(name))
          Template(parents, self, anyvalConstructor :: body)
        else
          gen.mkTemplate(gen.mkParents(mods, parents, parentPos),
                         self, constrMods, vparamss, body, o2p(tstart))
      }
    }

/* -------- TEMPLATES ------------------------------------------- */

    /** {{{
     *  TemplateBody ::= [nl] `{` TemplateStatSeq `}`
     *  }}}
     * @param isPre specifies whether in early initializer (true) or not (false)
     */
    def templateBody(isPre: Boolean) = inBraces(templateStatSeq(isPre = isPre)) match {
      case (self, Nil)  => (self, EmptyTree.asList)
      case result       => result
    }
    def templateBodyOpt(parenMeansSyntaxError: Boolean): (ValDef, List[Tree]) = {
      newLineOptWhenFollowedBy(LBRACE)
      if (in.token == LBRACE) {
        templateBody(isPre = false)
      } else {
        if (in.token == LPAREN) {
          if (parenMeansSyntaxError) syntaxError(s"traits or objects may not have parameters", skipIt = true)
          else abort("unexpected opening parenthesis")
        }
        (noSelfType, List())
      }
    }

    /** {{{
     *  Refinement ::= [nl] `{` RefineStat {semi RefineStat} `}`
     *  }}}
     */
    def refinement(): List[Tree] = inBraces(refineStatSeq())

/* -------- STATSEQS ------------------------------------------- */

  /** Create a tree representing a packaging. */
    def makePackaging(start: Offset, pkg: Tree, stats: List[Tree]): PackageDef = pkg match {
      case x: RefTree => atPos(start, pkg.pos.point)(PackageDef(x, stats))
    }

    def makeEmptyPackage(start: Offset, stats: List[Tree]): PackageDef = (
      makePackaging(start, atPos(start, start, start)(Ident(nme.EMPTY_PACKAGE_NAME)), stats)
    )

    def statSeq(stat: PartialFunction[Token, List[Tree]], errorMsg: String = "illegal start of definition"): List[Tree] = {
      val stats = new ListBuffer[Tree]
      def default(tok: Token) =
        if (isStatSep) Nil
        else syntaxErrorOrIncompleteAnd(errorMsg, skipIt = true)(Nil)
      while (!isStatSeqEnd) {
        stats ++= stat.applyOrElse(in.token, default)
        acceptStatSepOpt()
      }
      stats.toList
    }

    /** {{{
     *  TopStatSeq ::= TopStat {semi TopStat}
     *  TopStat ::= Annotations Modifiers TmplDef
     *            | Packaging
     *            | package object ObjectDef
     *            | Import
     *            |
     *  }}}
     */
    def topStatSeq(): List[Tree] = statSeq(topStat, errorMsg = "expected class or object definition")
    def topStat: PartialFunction[Token, List[Tree]] = {
      case PACKAGE  =>
        packageOrPackageObject(in.skipToken()) :: Nil
      case IMPORT =>
        in.flushDoc
        importClause()
      case _ if isAnnotation || isTemplateIntro || isModifier =>
        joinComment(topLevelTmplDef :: Nil)
    }

    /** {{{
     *  TemplateStatSeq  ::= [id [`:` Type] `=>`] TemplateStats
     *  }}}
     * @param isPre specifies whether in early initializer (true) or not (false)
     */
    def templateStatSeq(isPre : Boolean): (ValDef, List[Tree]) = {
      var self: ValDef = noSelfType
      var firstOpt: Option[Tree] = None
      if (isExprIntro) checkNoEscapingPlaceholders {
        in.flushDoc
        val first = expr(InTemplate) // @S: first statement is potentially converted so cannot be stubbed.
        if (in.token == ARROW) {
          first match {
            case Typed(tree @ This(tpnme.EMPTY), tpt) =>
              self = atPos(tree.pos union tpt.pos) { makeSelfDef(nme.WILDCARD, tpt) }
            case _ =>
              convertToParam(first) match {
                case tree @ ValDef(_, name, tpt, EmptyTree) if (name != nme.ERROR) =>
                  self = atPos(tree.pos union tpt.pos) { makeSelfDef(name, tpt) }
                case _ =>
              }
          }
          in.nextToken()
        } else {
          firstOpt = Some(first)
          acceptStatSepOpt()
        }
      }
      (self, firstOpt ++: templateStats())
    }

    /** {{{
     *  TemplateStats    ::= TemplateStat {semi TemplateStat}
     *  TemplateStat     ::= Import
     *                     | Annotations Modifiers Def
     *                     | Annotations Modifiers Dcl
     *                     | Expr1
     *                     | super ArgumentExprs {ArgumentExprs}
     *                     |
     *  }}}
     */
    def templateStats(): List[Tree] = checkNoEscapingPlaceholders { statSeq(templateStat) }
    def templateStat: PartialFunction[Token, List[Tree]] = {
      case IMPORT =>
        in.flushDoc
        importClause()
      case _ if isDefIntro || isModifier || isAnnotation =>
        joinComment(nonLocalDefOrDcl)
      case _ if isExprIntro =>
        in.flushDoc
        statement(InTemplate) :: Nil
    }

    def templateOrTopStatSeq(): List[Tree] = statSeq(templateStat.orElse(topStat))

    /** {{{
     *  RefineStatSeq    ::= RefineStat {semi RefineStat}
     *  RefineStat       ::= Dcl
     *                     | type TypeDef
     *                     |
     *  }}}
     */
    def refineStatSeq(): List[Tree] = checkNoEscapingPlaceholders {
      val stats = new ListBuffer[Tree]
      while (!isStatSeqEnd) {
        stats ++= refineStat()
        if (in.token != RBRACE) acceptStatSep()
      }
      stats.toList
    }

    def refineStat(): List[Tree] =
      if (isDclIntro) { // don't IDE hook
        joinComment(defOrDcl(in.offset, NoMods))
      } else if (!isStatSep) {
        syntaxErrorOrIncomplete(
          "illegal start of declaration"+
          (if (inFunReturnType) " (possible cause: missing `=` in front of current method body)"
           else ""), skipIt = true)
        Nil
      } else Nil

    /** overridable IDE hook for local definitions of blockStatSeq
     *  Here's an idea how to fill in start and end positions.
    def localDef : List[Tree] = {
      atEndPos {
        atStartPos(in.offset) {
          val annots = annotations(skipNewLines = true)
          val mods = localModifiers() withAnnotations annots
          if (!(mods hasFlag ~(Flags.IMPLICIT | Flags.LAZY))) defOrDcl(mods)
          else List(tmplDef(mods))
        }
      } (in.offset)
    }
    */

    def localDef(implicitMod: Long): List[Tree] = {
      val annots = annotations(skipNewLines = true)
      val pos = in.offset
      val mods = (localModifiers() | implicitMod) withAnnotations annots
      val defs =
        if (!(mods hasFlag ~(Flags.IMPLICIT | Flags.LAZY))) defOrDcl(pos, mods)
        else List(tmplDef(pos, mods))

      in.token match {
        case RBRACE | CASE  => defs :+ setInPos(literalUnit)
        case _              => defs
      }
    }

    /** {{{
     *  BlockStatSeq ::= { BlockStat semi } [ResultExpr]
     *  BlockStat    ::= Import
     *                 | Annotations [implicit] [lazy] Def
     *                 | Annotations LocalModifiers TmplDef
     *                 | Expr1
     *                 |
     *  }}}
     */
    def blockStatSeq(): List[Tree] = checkNoEscapingPlaceholders {
      def acceptStatSepOptOrEndCase() = if (!isCaseDefEnd) acceptStatSepOpt()
      val stats = new ListBuffer[Tree]
      while (!isStatSeqEnd && !isCaseDefEnd) {
        if (in.token == IMPORT) {
          stats ++= importClause()
          acceptStatSepOptOrEndCase()
        }
        else if (isDefIntro || isLocalModifier || isAnnotation) {
          if (in.token == IMPLICIT) {
            val start = in.skipToken()
            if (isIdent) stats += implicitClosure(start, InBlock)
            else stats ++= localDef(Flags.IMPLICIT)
          } else {
            stats ++= localDef(0)
          }
          acceptStatSepOptOrEndCase()
        }
        else if (isExprIntro) {
          stats += statement(InBlock)
          acceptStatSepOptOrEndCase()
        }
        else if (isStatSep) {
          in.nextToken()
        }
        else {
          val addendum = if (isModifier) " (no modifiers allowed here)" else ""
          syntaxErrorOrIncomplete("illegal start of statement" + addendum, skipIt = true)
        }
      }
      stats.toList
    }

    /** {{{
     *  CompilationUnit ::= {package QualId semi} TopStatSeq
     *  }}}
     */
    def compilationUnit(): PackageDef = checkNoEscapingPlaceholders {
      def topstats(): List[Tree] = {
        val ts = new ListBuffer[Tree]
        while (in.token == SEMI) in.nextToken()
        val start = in.offset
        if (in.token == PACKAGE) {
          in.nextToken()
          if (in.token == OBJECT) {
            // note that joinComment is a hook method for scaladoc that takes a CBN arg -- tested by run/diagrams-filtering.scala
            ts ++= joinComment(List(packageObjectDef(start)))
            if (in.token != EOF) {
              acceptStatSep()
              ts ++= topStatSeq()
            }
          } else {
            in.flushDoc
            val pkg = pkgQualId()

            if (in.token == EOF) {
              ts += makePackaging(start, pkg, List())
            } else if (isStatSep) {
              in.nextToken()
              ts += makePackaging(start, pkg, topstats())
            } else {
              ts += inBraces(makePackaging(start, pkg, topStatSeq()))
              acceptStatSepOpt()
              ts ++= topStatSeq()
            }
          }
        } else {
          ts ++= topStatSeq()
        }
        ts.toList
      }

      resetPackage()
      topstats() match {
        case (stat @ PackageDef(_, _)) :: Nil => stat
        case stats                            =>
          val start =
            if (stats forall (_ == EmptyTree)) 0
            else {
              val wpos = wrappingPos(stats)
              if (wpos.isDefined) wpos.start
              else 0
            }

          makeEmptyPackage(start, stats)
      }
    }
  }
}
