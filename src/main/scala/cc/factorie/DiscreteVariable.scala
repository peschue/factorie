/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie
import scala.util.Random
import cc.factorie.la._

/** An IntegerVars with finite range 0...N.  
    For your own subclass MyDiscreteVar, you can set N=9 with Domain[MyDiscreteValue].size = 9.
    @author Andrew McCallum */
@DomainInSubclasses
trait DiscreteVars extends Variable with VectorVar {
  type VariableType <: DiscreteVars
  type DomainType <: DiscreteDomain[VariableType]
  // TODO Replace this mechanism with an Annotation? -akm
  class DomainClass extends DiscreteDomain[VariableType]()(null)
  final def domainSize: Int = domain.size // TODO Do we really want to have this method instead of using domain.size
  def minIntValue = 0
  def maxIntValue = domain.size - 1
  /** A cc.factorie.la.Vector representation of the value of this variable. */
  def vector: Vector
  /** A more efficient alternative to this.vector.activeDomain */
  def activeDomain: Iterable[Int]  // TODO Consider removing this? -akm
}

@DomainInSubclasses
trait DiscreteVar extends DiscreteVars {
  this: Variable =>
  type VariableType <: DiscreteVar
  //type ValueType <: DiscreteValue[VariableType]
  type ValueType = VariableType#DomainType#ValueType
  //type ValueType = DomainType#ValueType // TODO Consider domain.ValueType ?? Would this work?
  def value: ValueType //DiscreteDomain[VariableType]#DiscreteValue // ValueType
  /*@inline final*/ def intValue = value.asInstanceOf[DiscreteValue[VariableType]].index // TODO Try to get rid of this cast
  def vector = value // new SingletonBinaryVector(domain.size, intValue)
  def activeDomain = List(intValue)
  def ===(other: DiscreteVar) = value == other.value
  def !==(other: DiscreteVar) = value != other.value
}

@DomainInSubclasses
abstract class DiscreteVariable extends DiscreteVar with IterableSettings with QDistribution {
  // The base constructor must take no arguments because CategoricalVariable needs to create with a temporary value and do the lookup later.
  type VariableType <: DiscreteVariable
  def this(initialInt:Int) = { this(); _value = domain.getValue(initialInt).asInstanceOf[ValueType] } // TODO Get rid of this cast?
  private var _value: ValueType = null.asInstanceOf[ValueType]
  def value: ValueType = _value
  // override def domain: DomainType = _value.domain // TODO Consider this
  def set(newValue:ValueType)(implicit d:DiffList): Unit = if (newValue ne value) {
    assert((newValue eq null) || (value eq null) || newValue.domain == value.domain)
    if (d ne null) d += new DiscreteVariableDiff(_value, newValue)
    _value = newValue
  }
  /** You should never call this yourself.  Only used in CategoricalVariable initialization code. */
  protected def _set(newValue:ValueType) = {
    assert(newValue ne null)
    _value = newValue
  }
  def set(newInt:Int)(implicit d:DiffList): Unit = set(domain.getValue(newInt).asInstanceOf[ValueType])(d)
  def setRandomly(random:Random = cc.factorie.random, d:DiffList = null): Unit = set(random.nextInt(domain.size))(d)
  def settings = new SettingIterator {
    // TODO Base this on a domain.iterator
    var i = -1
    val max = domain.size - 1
    def hasNext = i < max
    def next(difflist:DiffList) = { i += 1; val d = newDiffList; set(i)(d); d }
    def reset = i = -1
    override def variable: DiscreteVariable.this.type = DiscreteVariable.this
  }
  case class DiscreteVariableDiff(oldValue: ValueType, newValue: ValueType) extends Diff {
    @inline final def variable: DiscreteVariable = DiscreteVariable.this
    @inline final def redo = _value = newValue
    @inline final def undo = _value = oldValue
    override def toString = "DiscreteVariableDiff("+oldValue+","+newValue+")"
      /*variable match { 
       case cv:CategoricalVar[_] if (oldIndex >= 0) => "DiscreteVariableDiff("+cv.domain.get(oldIndex)+"="+oldIndex+","+cv.domain.get(newIndex)+"="+newIndex+")"
       case _ => "IntegerVariableDiff("+oldIndex+","+newIndex+")"
      } */
  }
  // TODO But then this choice cannot be changed by subclasses :-(  Consider some implicit configuration instead.
  type QType = cc.factorie.generative.MutableProportions  // TODO Change this = to <:  Why wasn't this done before?
  def newQ = new cc.factorie.generative.DenseProportions(domain.size)
}

// TODO Remove this class!
case class Block(v1:BooleanVariable, v2:BooleanVariable) extends Variable with IterableSettings {
  type ValueType = (Boolean, Boolean)
  def value = (v1.booleanValue, v2.booleanValue)
  def settings: SettingIterator = new SettingIterator {
    var i = -1
    val max = 4
    def hasNext = i < max
    def next(difflist:DiffList): DiffList = {
      val d = newDiffList
      i += 1
      if (i % 2 == 1) v2.set(true)(d) else v2.set(false)(d)
      if (i > 1) v1.set(true)(d) else v1.set(false)(d)
      d
    }
    def reset = i = -1
  }
}

/** A collection of DiscreteVariables that can iterate over the cross-product of all of their values.  May be useful in the future for block-Gibbs-sampling?
    @author Andrew McCallum */
@deprecated("This will likely be removed in a future version.")
class DiscreteVariableBlock(vars:DiscreteVariable*) extends Variable with Seq[DiscreteVariable] with IterableSettings {
  type ValueType = List[Int]
  def value = _vars.map(_.intValue)
  private val _vars = vars.toList
  def length = _vars.length
  def apply(index:Int) = _vars.apply(index)
  def iterator = _vars.iterator
  override def foreach[U](f:(DiscreteVariable)=>U): Unit = _vars.foreach(f)
  def settings: SettingIterator = new SettingIterator {
    var i = -1
    val n = _vars.length
    val s = _vars.map(_.domain.size).toArray
    val max = s.foldLeft(1)(_*_)
    def hasNext = i < max
    def next(difflist:DiffList) = throw new Error // TODO Implement this properly { i += 1; val d = newDiffList; _vars(i%n).set(i/n)(d); d }
    def reset = i = -1
  }
}

@DomainInSubclasses
abstract class DiscreteObservation extends DiscreteVar {
  type VariableType <: DiscreteObservation
  def this(theInt:Int) = { this(); _value = domain.getValue(theInt) }
  var _value: ValueType = null.asInstanceOf[ValueType]
  /*@inline final*/ def value: ValueType = _value
  protected def _initializeValue(v:ValueType): Unit = {
    assert(v ne null)
    _value = v
  }
}
