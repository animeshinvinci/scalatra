package org.scalatra
package swagger

import grizzled.slf4j.Logger
import org.json4s.JsonDSL._
import org.json4s._
import org.scalatra.json.JsonSupport
import org.scalatra.swagger.DataType.{ ContainerDataType, ValueDataType }

/**
 * Trait that serves the resource and operation listings, as specified by the Swagger specification.
 */
trait SwaggerBaseBase extends Initializable with ScalatraBase { self: JsonSupport[_] with CorsSupport =>

  private lazy val logger = Logger[this.type]

  protected type ApiType <: SwaggerApi[_]

  protected implicit def jsonFormats: Formats
  protected def docToJson(doc: ApiType): JValue

  implicit override def string2RouteMatcher(path: String) = new RailsRouteMatcher(path)

  implicit class JsonAssocNonEmpty(left: JObject) {
    def ~!(right: JObject): JObject = {
      right.obj.headOption match {
        case Some((_, JArray(arr))) if arr.isEmpty => left.obj
        case Some((_, JObject(fs))) if fs.isEmpty => left.obj
        case _ => JObject(left.obj ::: right.obj)
      }
    }
  }

  /**
   * The name of the route to use when getting the index listing for swagger
   * defaults to optional resources.:format or /
   * @return The name of the route
   */
  protected def indexRoute: String = "resources"

  /**
   * Whether to include the format parameter in the index listing for swagger
   * defaults to false, the format parameter will not be present but is still optional.
   * @return true if the format parameter should be included in the returned json
   */
  protected def includeFormatParameter: Boolean = false

  abstract override def initialize(config: ConfigT): Unit = {
    super.initialize(config)
    if (swagger.swaggerVersion.startsWith("2.")) {
      get("/swagger.json") {
        renderSwagger2(swagger.docs.toList.asInstanceOf[List[ApiType]])
      }
    } else {
      logger.warn("Move to Swagger 2.0 because Swagger 1.x support will be dropped in Scalatra 2.7.0!!")

      get("""/([^.]+)*(?:\.(\w+))?""".r) {
        val doc :: fmt :: Nil = multiParams("captures").toList
        if (fmt != null) format = fmt
        swagger.doc(doc) match {
          case Some(d) ⇒ renderDoc(d.asInstanceOf[ApiType])
          case _ ⇒ halt(404)
        }
      }

      get("/(" + indexRoute + "(.:format))") {
        renderIndex(swagger.docs.toList.asInstanceOf[List[ApiType]])
      }

      options("/(" + indexRoute + "(.:format))") {}
    }
  }

  /**
   * Returns the Swagger instance responsible for generating the resource and operation listings.
   */
  protected implicit def swagger: SwaggerEngine[_ <: SwaggerApi[_]]

  @deprecated("Swagger 1.x support will be dropped in Scalatra 2.7.0", "2.6.0")
  protected def renderDoc(doc: ApiType): JValue = {
    val json = docToJson(doc) merge
      ("basePath" -> fullUrl("/", includeContextPath = swagger.baseUrlIncludeContextPath, includeServletPath = swagger.baseUrlIncludeServletPath)) ~
      ("swaggerVersion" -> swagger.swaggerVersion) ~
      ("apiVersion" -> swagger.apiVersion)
    val consumes = dontAddOnEmpty("consumes", doc.consumes)_
    val produces = dontAddOnEmpty("produces", doc.produces)_
    val protocols = dontAddOnEmpty("protocols", doc.protocols)_
    val authorizations = dontAddOnEmpty("authorizations", doc.authorizations)_
    val jsonDoc = (consumes andThen produces andThen protocols andThen authorizations)(json)
    //    println("The rendered json doc:\n" + jackson.prettyJson(jsonDoc))
    jsonDoc
  }

  private[this] def dontAddOnEmpty(key: String, value: List[String])(json: JValue) = {
    val v: JValue = if (value.nonEmpty) key -> value else JNothing
    json merge v
  }

  @deprecated("Swagger 1.x support will be dropped in Scalatra 2.7.0", "2.6.0")
  protected def renderIndex(docs: List[ApiType]): JValue = {
    ("apiVersion" -> swagger.apiVersion) ~
      ("swaggerVersion" -> swagger.swaggerVersion) ~
      ("apis" ->
        (docs.filter(_.apis.nonEmpty).toList map {
          doc =>
            ("path" -> (url(doc.resourcePath, includeServletPath = false, includeContextPath = false) + (if (includeFormatParameter) ".{format}" else ""))) ~
              ("description" -> doc.description)
        })) ~
        ("authorizations" -> swagger.authorizations.foldLeft(JObject(Nil)) { (acc, auth) =>
          acc merge JObject(List(auth.`type` -> Extraction.decompose(auth)))
        }) ~
        ("info" -> Option(swagger.apiInfo).map(Extraction.decompose(_)))
  }

  private[this] def generateDataType(dataType: DataType): List[JField] = {
    dataType match {
      case t: ValueDataType if t.qualifiedName.isDefined =>
        List(("$ref" -> s"#/definitions/${t.name}"))
      case t: ValueDataType =>
        List(("type" -> t.name), ("format" -> t.format))
      case t: ContainerDataType =>
        List(("type" -> "array"), ("items" -> generateDataType(t.typeArg.get)))
    }
  }

  protected def renderSwagger2(docs: List[ApiType]): JValue = {
    ("swagger" -> "2.0") ~
      ("info" ->
        ("title" -> swagger.apiInfo.title) ~
        ("version" -> swagger.apiVersion) ~
        ("description" -> swagger.apiInfo.description) ~
        ("termsOfService" -> swagger.apiInfo.termsOfServiceUrl) ~
        ("contact" -> (
          ("name" -> swagger.apiInfo.contact)
        )) ~
          ("license" -> (
            ("name" -> swagger.apiInfo.license) ~
            ("url" -> swagger.apiInfo.licenseUrl)
          ))) ~
            ("paths" ->
              (docs.filter(_.apis.nonEmpty).flatMap {
                doc =>
                  doc.apis.collect {
                    case api: SwaggerEndpoint[_] =>
                      (api.path -> api.operations.map { operation =>
                        (operation.method.toString.toLowerCase -> (
                          ("operationId" -> operation.operationId) ~
                          ("summary" -> operation.summary) ~!
                          ("schemes" -> operation.schemes) ~!
                          ("consumes" -> operation.consumes) ~!
                          ("produces" -> operation.produces) ~!
                          ("tags" -> operation.tags) ~
                          ("deprecated" -> operation.deprecated) ~
                          ("parameters" -> operation.parameters.map { parameter =>
                            ("name" -> parameter.name) ~
                              ("description" -> parameter.description) ~
                              ("required" -> parameter.required) ~
                              ("in" -> swagger2ParamTypeMapping(parameter.paramType.toString.toLowerCase)) ~~
                              (if (parameter.paramType.toString.toLowerCase == "body") {
                                List(JField("schema", JObject(JField("$ref", s"#/definitions/${parameter.`type`.name}"))))
                              } else {
                                generateDataType(parameter.`type`)
                              })
                          }) ~
                          ("responses" ->
                            ("200" ->
                              (if (operation.responseClass.name == "void") {
                                List(JField("description", "No response"))
                              } else {
                                List(JField("description", "OK"), JField("schema", generateDataType(operation.responseClass)))
                              })) ~
                              operation.responseMessages.map { response =>
                                (response.code.toString ->
                                  ("description", response.message) ~~
                                  response.responseModel.map { model =>
                                    List(JField("schema", JObject(JField("$ref", s"#/definitions/${model}"))))
                                  }.getOrElse(Nil))
                              }.toMap) ~!
                              ("security" -> (operation.authorizations.flatMap { requirement =>
                                swagger.authorizations.find(_.`keyname` == requirement).map { auth =>
                                  auth match {
                                    case a: OAuth => (requirement -> a.scopes)
                                    case b: ApiKey => (requirement -> List.empty)
                                    case _ => (requirement -> List.empty)
                                  }
                                }
                              }))
                        ))
                      }.toMap)
                  }.toMap
              }.toMap)) ~
              ("definitions" -> docs.flatMap { doc =>
                doc.models.map {
                  case (name, model) =>
                    (name ->
                      ("type" -> "object") ~
                      ("description" -> model.description) ~
                      ("discriminator" -> model.discriminator) ~
                      ("properties" -> model.properties.map {
                        case (name, property) =>
                          (name ->
                            ("description" -> property.description) ~~
                            generateDataType(property.`type`))
                      }.toMap) ~!
                      ("required" -> model.properties.collect {
                        case (name, property) if property.required => name
                      }))
                }
              }.toMap) ~
              ("securityDefinitions" -> (swagger.authorizations.flatMap { auth =>
                (auth match {
                  case a: OAuth => a.grantTypes.headOption.map { grantType =>
                    grantType match {
                      case g: ImplicitGrant => (a.keyname -> JObject(
                        JField("type", "oauth2"),
                        JField("description", a.description),
                        JField("flow", "implicit"),
                        JField("authorizationUrl", g.loginEndpoint.url),
                        JField("scopes", a.scopes.map(scope => JField(scope, scope)))
                      ))
                      case g: AuthorizationCodeGrant => (a.keyname -> JObject(
                        JField("type", "oauth2"),
                        JField("description", a.description),
                        JField("flow", "accessCode"),
                        JField("authorizationUrl", g.tokenRequestEndpoint.url),
                        JField("tokenUrl", g.tokenEndpoint.url),
                        JField("scopes", a.scopes.map(scope => JField(scope, scope)))
                      ))
                      case g: ApplicationGrant => ("oauth2" -> JObject(
                        JField("type", "oauth2"),
                        JField("description", a.description),
                        JField("flow", "application"),
                        JField("tokenUrl", g.tokenEndpoint.url),
                        JField("scopes", a.scopes.map(scope => JField(scope, scope)))
                      ))
                    }
                  }
                  case a: ApiKey => Some((a.keyname -> JObject(
                    JField("type", "apiKey"),
                    JField("description", a.description),
                    JField("name", a.keyname),
                    JField("in", a.passAs)
                  )))
                })
              }).toMap)
  }

  private def swagger2ParamTypeMapping(paramTypeName: String): String = {
    if (paramTypeName == "form") "formData" else paramTypeName
  }

  error {
    case t: Throwable =>
      t.printStackTrace()
      throw t
  }

}

trait SwaggerBase extends SwaggerBaseBase { self: ScalatraBase with JsonSupport[_] with CorsSupport =>
  type ApiType = Api
  implicit protected def jsonFormats: Formats = SwaggerSerializers.defaultFormats
  protected def docToJson(doc: Api): JValue = Extraction.decompose(doc)
  protected implicit def swagger: SwaggerEngine[ApiType]
}
