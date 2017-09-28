package com.twilio.swagger.codegen
package generators

import _root_.io.swagger.models._
import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.instances.all._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.twilio.swagger.codegen.extract.ScalaPackage
import com.twilio.swagger.codegen.terms.client._
import java.util.Locale
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.meta._

object AkkaHttpClientGenerator {
  object ClientTermInterp extends FunctionK[ClientTerm, Target] {
    private[this] def toDashedCase(s: String): String = {
      val lowercased = "^[A-Z]".r.replaceAllIn(s, m => m.group(1).toLowerCase(Locale.US))
      "([A-Z])".r.replaceAllIn(lowercased, m => '-' +: m.group(1).toLowerCase(Locale.US))
    }

    private[this] def formatClientName(clientName: Option[String]): Term.Param = {
      clientName.fold(
        param"clientName: String"
      )(name =>
        param"clientName: String = ${Lit.String(toDashedCase(name))}"
      )
    }

    private[this] def formatHost(schemes: Seq[String], host: Option[String]): Term.Param = (
      host.map {
        case v if !v.startsWith("http") =>
          val scheme = schemes.headOption.getOrElse("http")
          s"${scheme}://${v}"
        case v => v
      }.fold(param"host: String")(v => param"host: String = ${Lit.String(v)}")
    )


    def apply[T](term: ClientTerm[T]): Target[T] = term match {
      case ExtractOperations(paths) =>
        paths.map({ case (pathStr, path) =>
          Target.fromOption(Option(path.getOperationMap), "No operations defined")
            .map { operationMap =>
              operationMap.asScala.map { case (httpMethod, operation) =>
                ClientRoute(pathStr, httpMethod, operation)
              }
            }
        }).sequenceU.map(_.flatten)

      case GetClassName(operation) =>
        Target.pure(ScalaPackage(operation).toList.flatMap(_.split('.')))

      case GenerateClientOperation(className, ClientRoute(pathStr, httpMethod, operation), tracing, protocolElems) => {
        def toCamelCase(s: String): String = {
          val fromSnakeOrDashed = "[_-]([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase(Locale.US))
          "^([A-Z])".r.replaceAllIn(fromSnakeOrDashed, m => m.group(1).toLowerCase(Locale.US))
        }

        def generateUrlWithParams(path: String, pathArgs: Seq[ScalaParameter], qsArgs: Seq[ScalaParameter]): Target[Term] = {
          for {
            base <- SwaggerUtil.paths.generateUrlPathParams(path, pathArgs)({ case Term.Name(term) => Term.Name(toCamelCase(term)) })
            suffix = if (path.contains("?")) {
              Lit.String("&")
            } else {
              Lit.String("?")
            }

            baseTerm = q"${base} + ${suffix}"

            result = qsArgs.foldLeft[Term](baseTerm) { case (a, ScalaParameter(_, paramName, argName, _)) =>
              q""" $a + Formatter.addArg(${Lit.String(argName.value)}, ${paramName})"""
            }
          } yield result
        }

        def generateFormDataParams(parameters: Seq[ScalaParameter], needsMultipart: Boolean): Term = {
          if (parameters.isEmpty) {
            Term.Placeholder()
          } else if (needsMultipart) {
            def liftOptionFileTerm(tParamName: Term.Name, tName: Term.Name) = q"$tParamName.map(v => Multipart.FormData.BodyPart(${Lit.String(tName.value)}, v))"
            def liftFileTerm(tParamName: Term.Name, tName: Term.Name) = q"Some(Multipart.FormData.BodyPart(${Lit.String(tName.value)}, $tParamName))"
            def liftOptionTerm(tParamName: Term.Name, tName: Term.Name) = q"$tParamName.map(v => Multipart.FormData.BodyPart(${Lit.String(tName.value)}, Formatter.show(v)))"
            def liftTerm(tParamName: Term.Name, tName: Term.Name) = q"Some(Multipart.FormData.BodyPart(${Lit.String(tName.value)}, Formatter.show($tParamName)))"
            val args: Seq[Term] = parameters.foldLeft(Seq.empty[Term]) { case (a, ScalaParameter(param, paramName, argName, _)) =>
              val lifter: (Term.Name, Term.Name) => Term = param match {
                case param"$_: Option[BodyPartEntity]" => liftOptionFileTerm _
                case param"$_: Option[BodyPartEntity] = $_" => liftOptionFileTerm _
                case param"$_: BodyPartEntity" => liftFileTerm _
                case param"$_: BodyPartEntity = $_" => liftFileTerm _
                case param"$_: Option[$_]" => liftOptionTerm _
                case param"$_: Option[$_] = $_" => liftOptionTerm _
                case _ => liftTerm _
              }
                a :+ lifter(paramName, argName)
            }
            q"Seq(..$args)"
          } else {
            def liftOptionTerm(tParamName: Term.Name, tName: Term.Name) = q"(${Lit.String(tName.value)}, $tParamName.map(Formatter.show(_)))"
            def liftTerm(tParamName: Term.Name, tName: Term.Name) = q"(${Lit.String(tName.value)}, Some(Formatter.show($tParamName)))"
            val args: Seq[Term] = parameters.foldLeft(Seq.empty[Term]) { case (a, ScalaParameter(param, paramName, argName, _)) =>
              val lifter: (Term.Name, Term.Name) => Term = param match {
                case param"$_: Option[$_]" => liftOptionTerm _
                case param"$_: Option[$_] = $_" => liftOptionTerm _
                case _ => liftTerm _
              }
              a :+ lifter(paramName, argName)
            }
            q"Seq(..$args)"
          }
        }

        def generateHeaderParams(parameters: Seq[ScalaParameter]): Term = {
          def liftOptionTerm(tParamName: Term.Name, tName: Term.Name) = q"$tParamName.map(v => RawHeader(${Lit.String(tName.value)}, Formatter.show(v)))"
          def liftTerm(tParamName: Term.Name, tName: Term.Name) = q"Some(RawHeader(${Lit.String(tName.value)}, Formatter.show($tParamName)))"
          val args: Seq[Term] = parameters.foldLeft(Seq.empty[Term]) { case (a, ScalaParameter(param, paramName, argName, _)) =>
            val lifter: (Term.Name, Term.Name) => Term = param match {
              case param"$_: Option[$_]" => liftOptionTerm _
              case param"$_: Option[$_] = $_" => liftOptionTerm _
              case _ => liftTerm _
            }
            a :+ lifter(paramName, argName)
          }
          q"scala.collection.immutable.Seq[Option[HttpHeader]](..$args).flatten"
        }

        def build(methodName: String, httpMethod: HttpMethod, urlWithParams: Term, formDataParams: Term, formDataNeedsMultipart: Boolean, headerParams: Term, responseTypeRef: Type, tracing: Boolean
            )(tracingArgsPre: Seq[ScalaParameter], tracingArgsPost: Seq[ScalaParameter], pathArgs: Seq[ScalaParameter], qsArgs: Seq[ScalaParameter], formArgs: Seq[ScalaParameter], body: Option[ScalaParameter], headerArgs: Seq[ScalaParameter], extraImplicits: Seq[Term.Param]
            ): Defn = {
          val implicitParams = Option(extraImplicits).filter(_.nonEmpty)
          val defaultHeaders = param"headers: scala.collection.immutable.Seq[HttpHeader] = Nil"
          val fallbackHttpBody: Option[(Term, Type)] = if (Set(HttpMethod.PUT, HttpMethod.POST) contains httpMethod) Some((q"HttpEntity.Empty", t"HttpEntity.Strict")) else None
          val safeBody: Option[(Term, Type)] = body.map(sp => (sp.argName, sp.argType)).orElse(fallbackHttpBody)
          val entity: Term = if (formArgs.nonEmpty) {
            if (formDataNeedsMultipart) {
              q"""Multipart.FormData(Source.fromIterator { () => $formDataParams.flatten.iterator }).toEntity"""
            } else {
              q"""FormData($formDataParams.collect({ case (n, Some(v)) => (n, v) }): _*).toEntity"""
            }
          } else {
            q"HttpEntity.Empty"
          }

          val methodBody: Term = if (tracing) {
            val tracingLabel = q"""s"$${clientName}:$${methodName}""""
            q"""
            {
              traceBuilder(s"$${clientName}:$${methodName}") { propagate =>
                val allHeaders = headers ++ $headerParams
                wrap[${responseTypeRef}](httpClient(propagate(HttpRequest(method = HttpMethods.${Term.Name(httpMethod.toString.toUpperCase)}, uri = ${urlWithParams}, entity = ${entity}, headers = allHeaders))))
              }
            }
            """
          } else {
            q"""
            {
              val allHeaders = headers ++ $headerParams
              wrap[${responseTypeRef}](httpClient(HttpRequest(method = HttpMethods.${Term.Name(httpMethod.toString.toUpperCase)}, uri = ${urlWithParams}, entity = ${entity}, headers = allHeaders)))
            }
            """
          }

          val arglists: Seq[Seq[Term.Param]] = Seq(
            Some((tracingArgsPre.map(_.param) ++ pathArgs.map(_.param) ++ qsArgs.map(_.param) ++ formArgs.map(_.param) ++ body.map(_.param) ++ headerArgs.map(_.param) ++ tracingArgsPost.map(_.param)) :+ defaultHeaders),
            implicitParams
          ).flatten

          q"""
          def ${Term.Name(methodName)}(...${arglists}): EitherT[Future, Either[Throwable, HttpResponse], $responseTypeRef] = $methodBody
          """
        }

        for {
          // Placeholder for when more functions get logging
          _ <- Target.pure(())

          formDataNeedsMultipart = Option(operation.getConsumes).exists(_.contains("multipart/form-data"))

        // Get the response type
          responseTypeRef: Type = SwaggerUtil.getResponseType(httpMethod, operation)

        // Insert the method parameters
          httpMethodStr: String = httpMethod.toString.toLowerCase
          methodName = Option(operation.getOperationId).getOrElse(s"$httpMethodStr $pathStr")


          filterParamBy = ScalaParameter.filterParams(Option(operation.getParameters).map(_.asScala).toIndexedSeq.flatten, protocolElems)
          headerArgs = filterParamBy("header")
          pathArgs = filterParamBy("path")
          qsArgs = filterParamBy("query")
          bodyArgs = filterParamBy("body").headOption
          formArgs = filterParamBy("formData")

          // Generate the url with path, query parameters
          urlWithParams <- generateUrlWithParams(pathStr, pathArgs, qsArgs)

          // Generate FormData arguments
          formDataParams = generateFormDataParams(formArgs, formDataNeedsMultipart)
          // Generate header arguments
          headerParams = generateHeaderParams(headerArgs)

          tracingArgsPre = if (tracing) Seq(ScalaParameter.fromParam(param"traceBuilder: TraceBuilder[Either[Throwable, HttpResponse], ${responseTypeRef}]")) else Seq.empty
          tracingArgsPost = if (tracing) Seq(ScalaParameter.fromParam(param"methodName: String = ${Lit.String(toDashedCase(methodName))}")) else Seq.empty
          extraImplicits = Seq.empty
          defn = build(methodName, httpMethod, urlWithParams, formDataParams, formDataNeedsMultipart, headerParams, responseTypeRef, tracing)(tracingArgsPre, tracingArgsPost, pathArgs, qsArgs, formArgs, bodyArgs, headerArgs, extraImplicits)
        } yield defn
      }

      case GetFrameworkImports(tracing) => Target.pure(Seq(
        q"import akka.http.scaladsl.model._"
      , q"import akka.http.scaladsl.model.headers.RawHeader"
      , q"import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller, FromEntityUnmarshaller}"
      , q"import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}"
      , q"import akka.http.scaladsl.util.FastFuture"
      , q"import akka.stream.Materializer"
      , q"import akka.stream.scaladsl.Source"
      , q"import akka.util.ByteString"
      , q"import cats.data.EitherT"
      , q"import scala.concurrent.{ExecutionContext, Future}"
      ))

      case GetImports(tracing) => Target.pure(Seq.empty)

      case GetExtraImports(tracing) => Target.pure(Seq.empty)

      case ClientClsArgs(tracingName, schemes, host, tracing) =>
        val ihc = param"implicit httpClient: HttpRequest => Future[HttpResponse]"
        val iec = param"implicit ec: ExecutionContext"
        val imat = param"implicit mat: Materializer"
        Target.pure(Seq(Seq(formatHost(schemes, host)) ++ (if (tracing) Some(formatClientName(tracingName)) else None), Seq(ihc, iec, imat)))

      case BuildCompanion(clientName, tracingName, schemes, host, ctorArgs, tracing) =>
        def extraConstructors(tracingName: Option[String], schemes: Seq[String], host: Option[String], tpe: Type.Name, ctorCall: Term.New, tracing: Boolean): Seq[Defn] = {
          val iec = param"implicit ec: ExecutionContext"
          val imat = param"implicit mat: Materializer"
          val tracingParams: Seq[Term.Param] = if (tracing) {
            Seq(formatClientName(tracingName))
          } else {
            Seq.empty
          }

          Seq(
            q"""
              def httpClient(httpClient: HttpRequest => Future[HttpResponse], ${formatHost(schemes, host)}, ..${tracingParams})($iec, $imat): ${tpe} = ${ctorCall}
            """
          )
        }

        def paramsToArgs(params: Seq[Seq[Term.Param]]): Seq[Seq[Term.Arg]] = params.map({ _.map(_.name.value).map(v => Term.Arg.Named(Term.Name(v), Term.Name(v))).to[Seq] }).to[Seq]
        val ctorCall: Term.New = {
          q"""
            new ${Ctor.Ref.Name(clientName)}(...${paramsToArgs(ctorArgs)})
          """
        }

        val companion: Defn.Object = q"""
            object ${Term.Name(clientName)} {
              def apply(...${ctorArgs}): ${Type.Name(clientName)} = ${ctorCall}
              ..${extraConstructors(tracingName, schemes, host, Type.Name(clientName), ctorCall, tracing)}
            }
          """
        Target.pure(companion)

      case BuildClient(clientName, tracingName, schemes, host, basePath, ctorArgs, clientCalls, tracing) =>
        val client =
          q"""
            class ${Type.Name(clientName)}(...${ctorArgs}) {
              val basePath: String = ${Lit.String(basePath.getOrElse(""))}

              private[this] def wrap[T: FromEntityUnmarshaller](resp: Future[HttpResponse]): EitherT[Future, Either[Throwable, HttpResponse], T] = {
                EitherT(
                  resp.flatMap(resp =>
                    if (resp.status.isSuccess) {
                      Unmarshal(resp.entity).to[T].map(Right.apply _)
                    } else {
                      FastFuture.successful(Left(Right(resp)))
                    }
                  ).recover {
                    case e: Throwable => Left(Left(e))
                  }
                )
              }

              ..$clientCalls
            }
          """
        Target.pure(client)
    }
  }
}