package com.waz

import java.util.concurrent.ConcurrentHashMap

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.utils.{CachedStorage, Managed, returning}
import com.waz.utils.events.{AggregatingSignal, EventStream, Signal, SourceStream}
import com.waz.utils.wrappers.DB

import scala.collection.generic.CanBuild
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.collection.breakOut

class InMemoryCachedStorage[K,V] extends CachedStorage[K, V] {

  private val storage = new ConcurrentHashMap[K, V]()

  private val onAdded2: SourceStream[Seq[(K, V)]] = EventStream()
  private val onUpdated2: SourceStream[Seq[(K, V, V)]] = EventStream()
  private val onChanged2: SourceStream[Seq[(K, V)]] = EventStream()

  override val onAdded: EventStream[Seq[V]] = onAdded2.map(_.map(_._2))
  override val onUpdated: EventStream[Seq[(V, V)]] = onUpdated2.map(_.map { case (_, prev, current) => prev -> current })
  override val onDeleted: SourceStream[Seq[K]] = EventStream()
  override val onChanged: EventStream[Seq[V]] = onChanged2.map(_.map(_._2))

  override protected def load(key: K)(implicit db: DB): Option[V] = ???
  override protected def load(keys: Set[K])(implicit db: DB): Seq[V] = ???
  override protected def save(values: Seq[V])(implicit db: DB): Unit = ???
  override protected def delete(keys: Iterable[K])(implicit db: DB): Unit = ???

  //TODO Can be removed. Why not just save instead?
  override protected def updateInternal(key: K, updater: V => V)(current: V): Future[Option[(V, V)]] = ???

  //TODO Remove this
  override def find[A, B](predicate: V => Boolean, search: DB => Managed[TraversableOnce[V]], mapping: V => A)(implicit cb: CanBuild[A, B]): Future[B] = ???


  def cached: Future[Map[K,V]] = Future(storage.asScala.toMap)
  def listCached: Future[Seq[V]] = cached.map(_.values.toSeq)
  def cachedLoad(keys: Traversable[K]): Future[Seq[(K, Option[V])]] =
    Future { keys.map(key => key -> Option(storage.get(key))).toSeq }
  override def filterCached(f: V => Boolean): Future[Vector[V]] = listCached.map(_.filter(f).toVector)
  override def foreachCached(f: V => Unit): Future[Unit] = listCached.map(_.foreach(f))
  //TODO Why not Future[Option[V]]?
  override def getRawCached(key: K): Option[V] = {
    val result = cachedLoad(List(key)).map { entries =>
      for {
        (_, maybeValue) <- entries.headOption
        value <- maybeValue
      } yield value
    }
    Await.result(result, 1.minute)
  }


  def cachePut(key: K, value: V): Unit = {
    storage.put(key, value)
  }

  override def printCache(): Unit = cached.foreach(println)

  override def deleteCached(predicate: V => Boolean): Future[Unit] =
    cached.map {
      _.foreach { case (key, value) => if (predicate(value)) storage.remove(key) }
    }

  override def onChanged(key: K): EventStream[V] =
    onUpdated2.map { _.find { _._1 == key }.map(_._3) }.collect { case Some(v) => v }
  override def onRemoved(key: K): EventStream[K] =
    onDeleted.map(_.find(_ == key)).collect { case Some(v) => v }
  override def optSignal(key: K): Signal[Option[V]] = {
    val changeOrDelete = onChanged(key).map(Option(_)).union(onRemoved(key).map(_ => Option.empty[V]))
    new AggregatingSignal[Option[V], Option[V]](changeOrDelete, get(key), { (_, v) => v })
  }
  override def signal(key: K): Signal[V] =
    optSignal(key).collect { case Some(v) => v }

  override def insert(value: V): Future[V] =
    for {
      _ <- storageSave(List(value))
      _ <- cachePut(storageKeyFromValue(value), value)
    } yield value

  override def insertAll(vs: Traversable[V]): Future[Set[V]] =
    updateOrCreateAll(vs.map { v => storageKeyFromValue(v) -> { _: Option[V] => v } }(breakOut))

  override def get(key: K): Future[Option[V]] =
    for {
      fromCache <- Future(getRawCached(key))
      result <-
        if (fromCache.nonEmpty) Future.successful(fromCache)
        else storageLoad(List(key)).map(_.headOption.map(_._2))
    } yield {
      if (fromCache.isEmpty) result.foreach(cachePut(key, _))
      result
    }

  override def getOrCreate(key: K, creator: => V): Future[V] =
    for {
      value <- get(key)

    }

  override def list(): Future[Seq[V]] = ???

  private def storageKeyFromValue(value: V): K = ???

  private def storageLoad(keys: Traversable[K]): Future[Seq[(K, Option[V])]] = {
    //In this implementation we use cache as durable storage
    cachedLoad(keys)
  }

  def storageSave(entries: Seq[V]): Future[Unit] = Future {
    //In this implementation we use cache as durable storage
    entries.foreach(value => cachePut(storageKeyFromValue(value), value))
  }

  def getAll2(keys: Traversable[K]): Future[Seq[(K, Option[V])]] = {
    for {
      cachedEntries <- cachedLoad(keys)
      cachedKeys = cachedEntries.map(_._1)
      missingKeys = keys.toSet -- cachedKeys
      storageEntries <- storageLoad(missingKeys)
      cachedEntriesMap = cachedEntries.toMap
      storageEntriesMap = storageEntries.toMap
    } yield keys.map { key =>
      val fromCache = cachedEntriesMap.getOrElse(key, None)
      lazy val fromStorage = storageEntriesMap.getOrElse(key, None)
      if (fromCache.isEmpty) fromStorage.foreach { value => cachePut(key, value) }
      key -> (fromCache orElse fromStorage)
    }.toSeq
  }

  override def getAll(keys: Traversable[K]): Future[Seq[Option[V]]] = getAll2(keys).map(_.map(_._2))

  override def update(key: K, updater: V => V): Future[Option[(V, V)]] = ???

  override def updateAll(updaters: collection.Map[K, V => V]): Future[Seq[(V, V)]] = ???

  override def updateAll2(keys: Iterable[K], updater: V => V): Future[Seq[(V, V)]] = ???

  override def updateOrCreate(key: K, updater: V => V, creator: => V): Future[V] = ???

  def updateOrCreateAll(updaters: Map[K, Option[V] => V]): Future[Set[V]] =
    updateOrCreateAll2(updaters.keys.toVector, { (key, v) => updaters(key)(v)})

  def updateOrCreateAll2(keys: Iterable[K], updater: (K, Option[V]) => V): Future[Set[V]] =
    for {
      entries <- getAll2(keys)
      entriesMap = entries.toMap
      toSave = Vector.newBuilder[V]
      added = Vector.newBuilder[(K,V)]
      updated = Vector.newBuilder[(K,V,V)]
      result = keys.map { key =>
        val current = entriesMap.getOrElse(key, None)
        val next = updater(key, current)
        current match {
          case Some(value) if value != next =>
            cachePut(key, next)
            toSave += next
            updated += ((key, value, next))
          case None =>
            cachePut(key, next)
            toSave += next
            added += key -> next
          case _ => // unchanged, ignore
        }
        next
      }
      _ <- storageSave(toSave.result())
    } yield {
      val addedResult = added.result
      val updatedResult = updated.result
      if (addedResult.nonEmpty) onAdded ! addedResult
      if (updatedResult.nonEmpty) onUpdated ! updatedResult
      result
    }

  //TODO Dangerous method! Has to be removed!!
  override def put(key: K, value: V): Future[V] =
    for {
      _ <- storageSave(List(value))
      _ <- cachePut(key, value)
    } yield value

  override def remove(key: K): Future[Unit] = ???

  override def removeAll(keys: Iterable[K]): Future[Unit] = ???

  override def cacheIfNotPresent(key: K, value: V): Unit = ???

}
