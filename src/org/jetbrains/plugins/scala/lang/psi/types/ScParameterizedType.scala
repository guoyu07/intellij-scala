package org.jetbrains.plugins.scala
package lang
package psi
package types

/**
 * @author ilyas
 */

import com.intellij.psi._
import com.intellij.util.containers.ConcurrentWeakHashMap
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{PsiTypeParameterExt, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScTypeAlias, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypeParametersOwner
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager
import org.jetbrains.plugins.scala.lang.psi.types.api.{TypeParameterType, TypeVisitor, ValueType}
import org.jetbrains.plugins.scala.lang.psi.types.result.{Success, TypingContext}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScTypeUtil.AliasType

import scala.collection.immutable.{HashSet, ListMap, Map}

class ScParameterizedType private(val designator: ScType, val typeArgs: Seq[ScType]) extends ScalaType with ValueType {
  override protected def isAliasTypeInner: Option[AliasType] = {
    this match {
      case ScParameterizedType(ScDesignatorType(ta: ScTypeAlias), args) =>
        val genericSubst = ScalaPsiUtil.
          typesCallSubstitutor(ta.typeParameters.map(_.nameAndId), args)
        Some(AliasType(ta, ta.lowerBound.map(genericSubst.subst), ta.upperBound.map(genericSubst.subst)))
      case ScParameterizedType(p: ScProjectionType, args) if p.actualElement.isInstanceOf[ScTypeAlias] =>
        val ta: ScTypeAlias = p.actualElement.asInstanceOf[ScTypeAlias]
        val subst: ScSubstitutor = p.actualSubst
        val genericSubst = ScalaPsiUtil.
          typesCallSubstitutor(ta.typeParameters.map(_.nameAndId), args)
        val s = subst.followed(genericSubst)
        Some(AliasType(ta, ta.lowerBound.map(s.subst), ta.upperBound.map(s.subst)))
      case _ => None
    }
  }

  private var hash: Int = -1

  override def hashCode: Int = {
    if (hash == -1) {
      hash = designator.hashCode() + typeArgs.hashCode() * 31
    }
    hash
  }

  def substitutor: ScSubstitutor = {
    val res = ScParameterizedType.substitutorCache.get(this)
    if (res == null) {
      val res = substitutorInner
      ScParameterizedType.substitutorCache.put(this, res)
      res
    } else res
  }

  private def substitutorInner : ScSubstitutor = {
    def forParams[T](paramsIterator: Iterator[T], initial: ScSubstitutor, map: T => TypeParameterType): ScSubstitutor = {
      val argsIterator = typeArgs.iterator
      val builder = ListMap.newBuilder[(String, PsiElement), ScType]
      while (paramsIterator.hasNext && argsIterator.hasNext) {
        val p1 = map(paramsIterator.next())
        val p2 = argsIterator.next()
        builder += ((p1.nameAndId, p2))
        //res = res bindT ((p1.name, p1.getId), p2)
      }
      val subst = new ScSubstitutor(builder.result(), Map.empty, None)
      initial followed subst
    }
    designator match {
      case TypeParameterType(_, args, _, _, _) =>
        forParams(args.iterator, ScSubstitutor.empty, (p: TypeParameterType) => p)
      case _ => ScalaType.extractDesignated(designator, withoutAliases = false) match {
        case Some((owner: ScTypeParametersOwner, s)) =>
          forParams(owner.typeParameters.iterator, s, (tp: ScTypeParam) => ScalaPsiManager.typeVariable(tp))
        case Some((owner: PsiTypeParameterListOwner, s)) =>
          forParams(owner.getTypeParameters.iterator, s, (ptp: PsiTypeParameter) => ScalaPsiManager.typeVariable(ptp))
        case _ => ScSubstitutor.empty
      }
    }
  }

  override def removeAbstracts = ScParameterizedType(designator.removeAbstracts, typeArgs.map(_.removeAbstracts))

  override def recursiveUpdate(update: ScType => (Boolean, ScType), visited: HashSet[ScType]): ScType = {
    if (visited.contains(this)) {
      return update(this) match {
        case (true, res) => res
        case _ => this
      }
    }
    val newVisited = visited + this
    update(this) match {
      case (true, res) => res
      case _ =>
        ScParameterizedType(designator.recursiveUpdate(update, newVisited), typeArgs.map(_.recursiveUpdate(update, newVisited)))
    }
  }

  override def recursiveVarianceUpdateModifiable[T](data: T, update: (ScType, Int, T) => (Boolean, ScType, T),
                                           variance: Int = 1): ScType = {
    update(this, variance, data) match {
      case (true, res, _) => res
      case (_, _, newData) =>
        val des = ScalaType.extractDesignated(designator, withoutAliases = false) match {
          case Some((n: ScTypeParametersOwner, _)) =>
            n.typeParameters.map {
              case tp if tp.isContravariant => -1
              case tp if tp.isCovariant => 1
              case _ => 0
            }
          case _ => Seq.empty
        }
        ScParameterizedType(designator.recursiveVarianceUpdateModifiable(newData, update, variance),
          typeArgs.zipWithIndex.map {
            case (ta, i) =>
              val v = if (i < des.length) des(i) else 0
              ta.recursiveVarianceUpdateModifiable(newData, update, v * variance)
          })
    }
  }

  override def equivInner(r: ScType, uSubst: ScUndefinedSubstitutor, falseUndef: Boolean)
                         (implicit typeSystem: api.TypeSystem): (Boolean, ScUndefinedSubstitutor) = {
    var undefinedSubst = uSubst
    (this, r) match {
      case (ScParameterizedType(ScAbstractType(tpt, lower, upper), args), _) =>
        if (falseUndef) return (false, uSubst)
        val subst = new ScSubstitutor(Map(tpt.arguments.zip(args).map {
          case (tpt: TypeParameterType, tp: ScType) => (tpt.nameAndId, tp)
        }: _*), Map.empty, None)
        var conformance = r.conforms(subst.subst(upper), uSubst)
        if (!conformance._1) return (false, uSubst)
        conformance = subst.subst(lower).conforms(r, conformance._2)
        if (!conformance._1) return (false, uSubst)
        (true, conformance._2)
      case (ScParameterizedType(proj@ScProjectionType(projected, _, _), args), _) if proj.actualElement.isInstanceOf[ScTypeAliasDefinition] =>
        isAliasType match {
          case Some(AliasType(ta: ScTypeAliasDefinition, lower, _)) =>
            (lower match {
              case Success(tp, _) => tp
              case _ => return (false, uSubst)
            }).equiv(r, uSubst, falseUndef)
          case _ => (false, uSubst)
        }
      case (ScParameterizedType(ScDesignatorType(a: ScTypeAliasDefinition), args), _) =>
        isAliasType match {
          case Some(AliasType(ta: ScTypeAliasDefinition, lower, _)) =>
            (lower match {
              case Success(tp, _) => tp
              case _ => return (false, uSubst)
            }).equiv(r, uSubst, falseUndef)
          case _ => (false, uSubst)
        }
      case (ScParameterizedType(_, _), ScParameterizedType(designator1, typeArgs1)) =>
        var t = designator.equiv(designator1, undefinedSubst, falseUndef)
        if (!t._1) return (false, undefinedSubst)
        undefinedSubst = t._2
        if (typeArgs.length != typeArgs1.length) return (false, undefinedSubst)
        val iterator1 = typeArgs.iterator
        val iterator2 = typeArgs1.iterator
        while (iterator1.hasNext && iterator2.hasNext) {
          t = iterator1.next().equiv(iterator2.next(), undefinedSubst, falseUndef)
          if (!t._1) return (false, undefinedSubst)
          undefinedSubst = t._2
        }
        (true, undefinedSubst)
      case _ => (false, undefinedSubst)
    }
  }

  /**
   * @return Some((designator, paramType, returnType)), or None
   */
  def getPartialFunctionType: Option[(ScType, ScType, ScType)] = {
    getStandardType("scala.PartialFunction") match {
      case Some((typeDef, Seq(param, ret))) => Some((ScDesignatorType(typeDef), param, ret))
      case None => None
    }
  }

  /**
   * @param  prefix of the qualified name of the type
   * @return (typeDef, typeArgs)
   */
  private def getStandardType(prefix: String): Option[(ScTypeDefinition, Seq[ScType])] = {
    def startsWith(clazz: PsiClass, qualNamePrefix: String) = clazz.qualifiedName != null && clazz.qualifiedName.startsWith(qualNamePrefix)

    designator.extractClassType() match {
      case Some((clazz: ScTypeDefinition, sub)) if startsWith(clazz, prefix) =>
        val result = clazz.getType(TypingContext.empty)
        result match {
          case Success(t, _) =>
            val substituted = (sub followed substitutor).subst(t)
            substituted match {
              case pt: ScParameterizedType =>
                Some((clazz, pt.typeArgs))
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }


  override def visitType(visitor: TypeVisitor) = visitor match {
    case scalaVisitor: ScalaTypeVisitor => scalaVisitor.visitParameterizedType(this)
    case _ =>
  }

  override def typeDepth: Int = {
    val depths = typeArgs.map(_.typeDepth)
    if (depths.isEmpty) designator.typeDepth //todo: shouldn't be possible
    else designator.typeDepth.max(depths.max + 1)
  }

  override def isFinalType: Boolean = designator.isFinalType && typeArgs.filterBy(classOf[TypeParameterType])
    .forall(_.isInvariant)

  def canEqual(other: Any): Boolean = other.isInstanceOf[ScParameterizedType]

  override def equals(other: Any): Boolean = other match {
    case that: ScParameterizedType =>
      (that canEqual this) &&
        designator == that.designator &&
        typeArgs == that.typeArgs
    case _ => false
  }
}

object ScParameterizedType {
  val substitutorCache: ConcurrentWeakHashMap[ScParameterizedType, ScSubstitutor] = new ConcurrentWeakHashMap()

  def apply(designator: ScType, typeArgs: Seq[ScType]): ValueType = {
    val res = new ScParameterizedType(designator, typeArgs)
    designator match {
      case ScProjectionType(_: ScCompoundType, _, _) =>
        res.isAliasType match {
          case Some(AliasType(_: ScTypeAliasDefinition, _, upper)) => upper.getOrElse(res) match {
            case v: ValueType => v
            case _ => res
          }
          case _ => res
        }
      case _ => res
    }
  }

  def unapply(p: ScParameterizedType): Option[(ScType, Seq[ScType])] = {
    Some(p.designator, p.typeArgs)
  }
}

private[types] object CyclicHelper {
  def compute[R](pn1: PsiNamedElement, pn2: PsiNamedElement)(fun: () => R): Option[R] = {
    import org.jetbrains.plugins.scala.caches.ScalaRecursionManager._
    doComputationsForTwoElements(pn1, pn2, (p: Object, searches: Seq[Object]) => {
      !searches.contains(p)
    }, pn2, pn1, fun(), CYCLIC_HELPER_KEY)
  }
}