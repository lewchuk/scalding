/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding.typed

import cascading.pipe.CoGroup
import cascading.pipe.joiner.{Joiner => CJoiner, JoinerClosure}
import cascading.tuple.{Tuple => CTuple, Fields, TupleEntry}

import com.twitter.scalding._

import scala.collection.JavaConverters._

class CoGrouped2[+K,V,W,+R](left: Grouped[K,V],
  right: Grouped[K,W],
  joiner: (K, Iterator[V], Iterable[W]) => Iterator[R])
  extends KeyedList[K,R] with java.io.Serializable {

  override lazy val toTypedPipe : TypedPipe[(K,R)] = {
    // Actually make a new coGrouping:
    assert(left.reduceStep.valueOrdering == None, "secondary sorting unsupported in CoGrouped2")
    assert(right.reduceStep.valueOrdering == None, "secondary sorting unsupported in CoGrouped2")

    import Dsl._
    import RichPipe.assignName

    // It is important to use the right.ordering since
    // it is the superclass of K in Grouped.cogroup.
    val leftGroupKey = RichFields(StringField("key")(right.reduceStep.keyOrdering, None))
    val rightGroupKey = RichFields(StringField("key1")(right.reduceStep.keyOrdering, None))
    val cascadingJoiner = new Joiner2(left.reduceStep.streamMapping,
      right.reduceStep.streamMapping,
      joiner)
    /*
     * we have to have 4 fields, but we only want key and value.
     * Cascading requires you have the same number coming in as out.
     * in the first case, we introduce (null0, null1), in the second
     * we have (key1, value1), but they are then discarded:
     */
    val newPipe = if(left.reduceStep.mapped == right.reduceStep.mapped) {
      // This is a self-join
      val NUM_OF_SELF_JOINS = 1
      new CoGroup(assignName(left.reduceStep.mappedPipe), leftGroupKey,
        NUM_OF_SELF_JOINS,
        // A self join with two fields results in 4 declared:
        new Fields("key","value","null0", "null1"),
        cascadingJoiner)
    }
    else {
      new CoGroup(assignName(left.reduceStep.mappedPipe), leftGroupKey,
        assignName(right.reduceStep.mappedPipe.rename(('key, 'value) -> ('key1, 'value1))),
        rightGroupKey,
        cascadingJoiner)
    }

    val reducers = scala.math.max(left.reducers, right.reducers)
    /*
     * the Joiner only populates the first two fields, the second two
     * are null. We then project out at the end of the method.
     */
    val pipeWithRed = RichPipe.setReducers(newPipe, reducers).project('key, 'value)
    //Construct the new TypedPipe
    TypedPipe.from[(K,R)](pipeWithRed, ('key, 'value))
  }

  override def mapValueStream[U](fn: Iterator[R] => Iterator[U]) = {
    new CoGrouped2[K,V,W,U](left, right, {(k,vit,wit) => fn(joiner(k,vit,wit))})
  }
}

class Joiner2[K,V,W,R](leftGetter : Iterator[CTuple] => Iterator[V],
  rightGetter: Iterator[CTuple] => Iterator[W],
  joiner: (K, Iterator[V], Iterable[W]) => Iterator[R]) extends CJoiner {

  import Joiner._

  override def getIterator(jc: JoinerClosure) = {
    // The left one cannot be iterated multiple times on Hadoop:
    val (lkopt, left) = getKeyValue[K](jc.getIterator(0))
    // It's safe to call getIterator more than once on index > 0
    val (rkopt, _) = getKeyValue[K](jc.getIterator(1))
    // Try to get from the right-hand-side
    val goodKey = lkopt.orElse(rkopt).get

    val rightIterable = new Iterable[W] with java.io.Serializable {
      def iterator = rightGetter(jc.getIterator(1).asScala.map { TupleConverter.tupleAt(1) })
    }

    joiner(goodKey, leftGetter(left), rightIterable).map { rval =>
      // There always has to be four resulting fields
      // or otherwise the flow planner will throw
      val res = CTuple.size(4)
      res.set(0, goodKey)
      res.set(1, rval)
      res
    }.asJava
  }
  override val numJoins = 1
}
