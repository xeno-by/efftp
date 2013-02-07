package scala.tools.nsc.effects

trait Infer { self: EffectDomain =>
  import global._
  import lattice._

  /**
   * This class is used to report effect mismatch errors.
   */
  abstract class EffectReporter {
    protected def issueError(tree: Tree, msg: String): Unit
    protected def setError(tree: Tree): Unit

    private def reportError(tree: Tree, msg: String) {
      issueError(tree, msg)
      setError(tree)
    }

    private def mismatchMsg(expected: Effect, found: Effect, detailsMsg: Option[String]) =
      "effect type mismatch;\n found   : " + found + "\n required: " + expected +
      detailsMsg.map("\n"+_).getOrElse("")

    def error(expected: Effect, found: Effect, tree: Tree, detailsMsg: Option[String]) {
      reportError(tree, mismatchMsg(expected, found, detailsMsg))
    }
  }

  /**
   * The context which is passed through effect inference.
   *
   * @param expected  The expected effect. If defined, an error message is issued if the inferred effect does not
   *                  conform to the expected effect.
   * @param relEnv    The relative effects which are active for inferring the effect of the expression.
   * @param reporter  The error reporter.
   * @param errorInfo An optional message which is printed together with every effect mismatch error message.
   */
  case class EffectContext(expected: Option[Effect],
                           relEnv: List[RelEffect],
                           reporter: EffectReporter,
                           errorInfo: Option[String])


  /**
   * Check that `found` conforms to the expected effect in `ctx` and report an error if not.
   * Returns `found` if no error is issued, `bottom` otherwise (to prevent additional spurious errors).
   */
  def checkConform(found: Effect, tree: Tree, ctx: EffectContext): Effect = ctx.expected match {
    case Some(expected) if !(found <= expected) =>
      ctx.reporter.error(expected, found, tree, ctx.errorInfo)
      bottom

    case _ =>
      found
  }


  /**
   * This method implements the effect computation, it returns the effect of `tree`.
   *
   * Concrete effect domains can override this method to assign effects to specific trees.
   */
  def computeEffectImpl(tree: Tree, ctx: EffectContext): Effect = {
    lazy val sym = tree.symbol
    tree match {

      /*** Method Invocations ***/

      case _: Apply =>
        computeApplyEffect(tree, ctx)

      case _: TypeApply =>
        computeApplyEffect(tree, ctx)

      case _: Select if sym.isMethod =>
        computeApplyEffect(tree, ctx)

      case _: Ident if sym.isMethod =>
        // parameterless local methods are applied using an `Ident` tree
        computeApplyEffect(tree, ctx)


      /*** Modules, Lazy Vals and By-Name Params ***/

      case _: Select if sym.isModule =>
        // selection of a module has the effect of the module constructor
        val constr = sym.moduleClass.primaryConstructor
        latent(constr, Map(), Map(), ctx)

      case (_: Select | _: Ident) if sym.isLazy =>
        latent(sym, Map(), Map(), ctx)

      case _: ValDef if sym.isLazy =>
        // lazy val definitions have no effect
        bottom

      case (_: Select | _: Ident) if (sym.isByNameParam || definitions.isByNameParamType(sym.tpe)) =>
        // if the enclosing method is annotated `@rel(x)` for by-name parameter `x`, delay the effect
        val isField = sym.isParamAccessor
        val hasRel = ctx.relEnv.exists({
          case RelEffect(ParamLoc(relSym), None) =>
            // for primary constructor expressions, references to by-name parameters are in fact references to the
            // corresponding field. so we need to match the field symbol (sym) to the constr param (relSym)
            relSym == sym || (isField && relSym.name == sym.name && relSym.owner.owner == sym.owner)
          case _ =>
            false
        })
        if (hasRel) bottom
        else top


      /*** Definitions ***/

      case _: TypeDef | _: DefDef | _: ClassDef | _: ModuleDef | _: Function =>
        // type, method, class and function definitions have no effect
        bottom


      /*** Otherwise, recurse into Subtrees ***/

      case _ =>
        val childEffs = computeChildEffects(tree, ctx)
        joinAll(childEffs: _*)
    }
  }


  /**
   * Computes the effect of `tree` and verifies that it conforms to the expected effect in `ctx` if it
   * is defined. Instead of overriding this methods, effect domains should override `computeEffectImpl`.
   */
  final def computeEffect(tree: Tree, ctx: EffectContext): Effect = {
    checkConform(computeEffectImpl(tree, ctx), tree, ctx)
  }


  /**
   * Computes the effects of all children of `tree`.
   */
  def computeChildEffects(tree: Tree, ctx: EffectContext) = {
    class ChildEffectsTraverser(ctx: EffectContext) extends Traverser {
      val res = new collection.mutable.ListBuffer[Effect]()

      /* Here we don't call `super.traverse`, i.e. we don't continue into subtrees any deeper */
      override def traverse(tree: Tree) { res += computeEffect(tree, ctx) }

      /* `super.traverse` calls `traverse` for each subtree of `tree` */
      def computeChildEffects(tree: Tree): List[Effect] = {
        super.traverse(tree)
        res.toList
      }
    }

    new ChildEffectsTraverser(ctx).computeChildEffects(tree)
  }

  /**
   * Computes the effect of a method application. This effect consists of three parts:
   *
   *  - the effect of the method selection expression
   *  - the effects of the argument expressions
   *  - the latent (annotated) effect of the method
   *
   * If the function invocation is covered by the relative effect environment in `ctx`, the
   * latent effect does not have to be considered and is therefore `bottom`.
   *
   * Otherwise, all relative effects of the invoked method are expanded using the concrete
   * argument types, which implements effect polymorphism.
   */
  def computeApplyEffect(tree: Tree, ctx: EffectContext): Effect = {
    val treeInfo.Applied(fun, _, argss) = tree
    val args = argss.flatten

    val funSym = fun.symbol
    val params = funSym.paramss.flatten
    // calling inferEffect on `fun` would result in an infinite loop
    val funEff   = fun match {
      case Select(qual, _) => computeEffect(qual, ctx)
      case Ident(_) => bottom
    }

    // for by-name parameters, the effect of the argument expression is only included if the
    // callee has a `@rel(x)` annotation for the by-name parameter x (done by `latent` below)
    val (byNameEffs: Map[Symbol, Effect], byValEffs: List[Effect]) = {
      val (byNameParamArgs, byValueParmArgs) = (params zip args).partition(_._1.isByNameParam)
      // use no expected effect for inferring by-name parameter effects, otherwise effect mismatch
      // errors might be issued unnecessarily
      lazy val noExpectedCtx = ctx.copy(expected = None)
      val byNameEffs0 = byNameParamArgs.map({
        case (param, arg) => (param, computeEffect(arg, noExpectedCtx))
      }).toMap
      val byValEffs0 = byValueParmArgs.map(pa => computeEffect(pa._2, ctx))
      (byNameEffs0, byValEffs0)
    }

    val lat = {
      if (hasRelativeEffect(fun, ctx)) bottom
      else {
        val paramLocs = params.map(ParamLoc)
        // @TODO: should probably add ThisLoc to the map. can the type of this ever be more specific?
        latent(funSym, paramLocs.zip(args.map(argTpeAndLoc)).toMap, byNameEffs, ctx)
      }
    }
    funEff u (byValEffs :\ bottom)(_ u _) u lat
  }


  /**
   * Returns the type and location of a tree which is passed to a method as argument.
   */
  private val argTpeAndLoc: Tree => (Type, Option[Loc]) = arg => {
    val tp = arg.tpe
    arg match {
      case Ident(_) if arg.symbol.owner.isMethod =>
        (tp, Some(ParamLoc(arg.symbol)))
      case This(_) =>
        (tp, Some(ThisLoc(arg.symbol)))
      case _ =>
        (tp, None)
    }
  }

  /**
   * True if the method `fun` exists in the relative effect environment in `ctx`, i.e. if it is
   * a method call on a parameter of an enclosing method, and that method is effect-polymorphic in `fun`.
   */
  private def hasRelativeEffect(fun: Tree, ctx: EffectContext): Boolean = {
    val sym = fun.symbol
    fun match {
      case Select(id @ Ident(_), _) =>
        val r = RelEffect(ParamLoc(id.symbol), Some(sym))
        lteRel(List(r), ctx.relEnv)

      case Select(th @ This(_), _) =>
        val r = RelEffect(ThisLoc(th.symbol), Some(sym))
        lteRel(List(r), ctx.relEnv)

      case _ => false
    }
  }


  /**
   * The latent effect of `fun`, i.e. the maximal effect that might occur when invoking `fun`.
   *
   * @TODO: fixpoint computation needed (rel effects can go in circles!)?
   * @TODO: document what happens here (in general, argtps map specifically)
   *
   * @param argtps A map from the parameters of `fun` to the actual argument types which are passed
   *               in the invocation. These types might be more specific than the parameter types of
   *               `fun`, which implements effect-polymorphism.
   * @param ctx    If `fun` has itself relative effects, but those relative effects are also in the
   *               environment ctx.relEnv (i.e. the enclosing function has the same relative effect), that
   *               relative effect can be ignored.
   */
  private def latent(fun: Symbol, argtps: Map[Loc, (Type, Option[Loc])],
                     byNameEffs: Map[Symbol, Effect], ctx: EffectContext): Effect = {
    defaultInvocationEffect(fun).getOrElse {
      val concrete = fromAnnotation(fun.info)
      val relEff = relEffects(fun)

      val expandedRelEff = relEff map { r =>
        if (lteRelOne(r, ctx.relEnv)) bottom
        else r match {
          case RelEffect(ParamLoc(param), None) if param.isByNameParam =>
            byNameEffs.getOrElse(param, top)

          case RelEffect(paramLoc, Some(fun)) if (argtps contains paramLoc) =>
            argtps(paramLoc) match {
              case (tp, Some(argLoc)) if lteRelOne(RelEffect(argLoc, Some(fun)), ctx.relEnv) =>
                // @TODO: document, example. if a parameter is forwarded to another method as parameter, detect rel effects
                bottom
              case (tp, _) =>
                val funSym = tp.member(fun.name).suchThat(m => m.overriddenSymbol(fun.owner) == fun || m == fun)
                latent(funSym, Map(), Map(), ctx)
            }

          case RelEffect(_, Some(fun)) =>
            latent(fun, Map(), Map(), ctx)

          case _ =>
            // todo: union of effects of all methods
            top
        }
      }
      concrete u (expandedRelEff :\ bottom)(_ u _)
    }
  }

}
