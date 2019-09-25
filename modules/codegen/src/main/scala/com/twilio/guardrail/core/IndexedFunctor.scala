package com.twilio.guardrail.core

import cats.data._

trait IndexedFunctor[F[_]] {
  type I
  def map[A, B](fa: F[A])(f: (I, A) => B): F[B]
  def label(i: I): Option[String]
}

object IndexedFunctor {
  implicit def indexedMap: IndexedFunctor[Map[String, ?]] = new IndexedFunctor[Map[String, ?]] {
    type I = String
    def map[A, B](fa: Map[String, A])(f: (I, A) => B): Map[String, B] = fa.map({ case (k, v) => (k, f(k, v)) })
    def label(i: String)                                              = Some('.' +: i)
  }

  implicit object indexedList extends IndexedFunctor[List] {
    type I = Int
    def map[A, B](fa: List[A])(f: (I, A) => B): List[B] = fa.zipWithIndex.map({ case (v, k) => f(k, v) })
    def label(i: Int)                                   = Some(s"[${i}]")
  }

  implicit def indexedMappish[F[_]](implicit F: IndexedFunctor[F]): IndexedFunctor[Mappish[F, String, ?]] = new IndexedFunctor[Mappish[F, String, ?]] {
    type I = String
    def map[A, B](fa: Mappish[F, String, A])(f: (I, A) => B): Mappish[F, String, B] = new Mappish(F.map(fa.value)({ case (_, (k, v)) => (k, f(k, v)) }))
    def label(i: I)                                                                 = Some(s"[${i}]")
  }

  implicit object indexedNonEmptyList extends IndexedFunctor[NonEmptyList] {
    type I = Int
    def map[A, B](fa: NonEmptyList[A])(f: (I, A) => B): NonEmptyList[B] = fa.zipWithIndex.map({ case (v, k) => f(k, v) })
    def label(i: Int)                                                   = Some(s"[${i}]")
  }

  implicit def indexedOption: IndexedFunctor[Option] = new IndexedFunctor[Option] {
    type I = Unit
    def map[A, B](fa: Option[A])(f: (I, A) => B): Option[B] = fa.map(f((), _))
    def label(i: Unit)                                      = None
  }
}
