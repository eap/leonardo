package org.broadinstitute.dsde.workbench.leonardo.service

import java.io.File

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.implicits._
import com.google.api.client.http.HttpResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.leonardo.config.{ClusterDefaultsConfig, ClusterFilesConfig, ClusterResourcesConfig, DataprocConfig, ProxyConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.leonardo.dao.google.{GoogleComputeDAO, GoogleDataprocDAO}
import org.broadinstitute.dsde.workbench.leonardo.db.{DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.leonardo.model.Cluster.LabelMap
import org.broadinstitute.dsde.workbench.leonardo.model.LeonardoJsonSupport._
import org.broadinstitute.dsde.workbench.leonardo.model.NotebookClusterActions._
import org.broadinstitute.dsde.workbench.leonardo.model.ProjectActions._
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.model.google.DataprocRole._
import org.broadinstitute.dsde.workbench.leonardo.model.google._
import org.broadinstitute.dsde.workbench.leonardo.monitor.ClusterMonitorSupervisor._
import org.broadinstitute.dsde.workbench.leonardo.util.BucketHelper
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail}
import slick.dbio.DBIO
import spray.json._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.control.NonFatal
import scala.util.Failure

case class AuthorizationError(email: Option[WorkbenchEmail] = None)
  extends LeoException(s"${email.map(e => s"'${e.value}'").getOrElse("Your account")} is unauthorized", StatusCodes.Unauthorized)

case class ClusterNotFoundException(googleProject: GoogleProject, clusterName: ClusterName)
  extends LeoException(s"Cluster ${googleProject.value}/${clusterName.value} not found", StatusCodes.NotFound)

case class ClusterAlreadyExistsException(googleProject: GoogleProject, clusterName: ClusterName, status: ClusterStatus)
  extends LeoException(s"Cluster ${googleProject.value}/${clusterName.value} already exists in ${status.toString} status", StatusCodes.Conflict)

case class ClusterCannotBeStoppedException(googleProject: GoogleProject, clusterName: ClusterName, status: ClusterStatus)
  extends LeoException(s"Cluster ${googleProject.value}/${clusterName.value} cannot be stopped in ${status.toString} status", StatusCodes.Conflict)

case class ClusterCannotBeStartedException(googleProject: GoogleProject, clusterName: ClusterName, status: ClusterStatus)
  extends LeoException(s"Cluster ${googleProject.value}/${clusterName.value} cannot be started in ${status.toString} status", StatusCodes.Conflict)

case class InitializationFileException(googleProject: GoogleProject, clusterName: ClusterName, errorMessage: String)
  extends LeoException(s"Unable to process initialization files for ${googleProject.value}/${clusterName.value}. Returned message: $errorMessage", StatusCodes.Conflict)

case class BucketObjectException(gcsUri: String)
  extends LeoException(s"The provided GCS URI is invalid or unparseable: ${gcsUri}", StatusCodes.BadRequest)

case class BucketObjectAccessException(userEmail: WorkbenchEmail, gcsUri: GcsPath)
  extends LeoException(s"${userEmail.value} does not have access to ${gcsUri.toUri}", StatusCodes.Forbidden)

case class DataprocDisabledException(errorMsg: String)
  extends LeoException(s"${errorMsg}", StatusCodes.Forbidden)

case class ParseLabelsException(labelString: String)
  extends LeoException(s"Could not parse label string: $labelString. Expected format [key1=value1,key2=value2,...]", StatusCodes.BadRequest)

case class IllegalLabelKeyException(labelKey: String)
  extends LeoException(s"Labels cannot have a key of '$labelKey'", StatusCodes.NotAcceptable)

class LeonardoService(protected val dataprocConfig: DataprocConfig,
                      protected val clusterFilesConfig: ClusterFilesConfig,
                      protected val clusterResourcesConfig: ClusterResourcesConfig,
                      protected val clusterDefaultsConfig: ClusterDefaultsConfig,
                      protected val proxyConfig: ProxyConfig,
                      protected val swaggerConfig: SwaggerConfig,
                      protected val gdDAO: GoogleDataprocDAO,
                      protected val googleComputeDAO: GoogleComputeDAO,
                      protected val googleIamDAO: GoogleIamDAO,
                      protected val leoGoogleStorageDAO: GoogleStorageDAO,
                      protected val petGoogleStorageDAO: String => GoogleStorageDAO,
                      protected val dbRef: DbReference,
                      protected val clusterMonitorSupervisor: ActorRef,
                      protected val authProvider: LeoAuthProvider,
                      protected val serviceAccountProvider: ServiceAccountProvider,
                      protected val whitelist: Set[String],
                      protected val bucketHelper: BucketHelper)
                     (implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val bucketPathMaxLength = 1024
  private val includeDeletedKey = "includeDeleted"

  private lazy val firewallRule = FirewallRule(
    name = FirewallRuleName(dataprocConfig.firewallRuleName),
    protocol = FirewallRuleProtocol(proxyConfig.jupyterProtocol),
    ports = List(FirewallRulePort(proxyConfig.jupyterPort.toString)),
    network = dataprocConfig.vpcNetwork.map(VPCNetworkName),
    targetTags = List(NetworkTag(dataprocConfig.networkTag)))

  // Startup script to install on the cluster master node. This is needed to support pause/resume clusters.
  private lazy val masterInstanceStartupScript: immutable.Map[String, String] = {
    immutable.Map("startup-script" -> s"docker exec -d ${dataprocConfig.jupyterServerName} /etc/jupyter/scripts/run-jupyter.sh")
  }

  def isWhitelisted(userInfo: UserInfo): Future[Boolean] = {
    if( whitelist contains userInfo.userEmail.value.toLowerCase ) {
      Future.successful(true)
    } else {
      Future.failed(new AuthorizationError(Some(userInfo.userEmail)))
    }
  }

  // Register this instance with the cluster monitor supervisor so our cluster monitor can potentially delete and recreate clusters
  clusterMonitorSupervisor ! RegisterLeoService(this)

  protected def checkProjectPermission(userInfo: UserInfo, action: ProjectAction, project: GoogleProject): Future[Unit] = {
    authProvider.hasProjectPermission(userInfo, action, project) map {
      case false => throw AuthorizationError(Option(userInfo.userEmail))
      case true => ()
    }
  }

  //Throws 404 and pretends we don't even know there's a cluster there, by default.
  //If the cluster really exists and you're OK with the user knowing that, set throw401 = true.
  protected def checkClusterPermission(userInfo: UserInfo, action: NotebookClusterAction, cluster: Cluster, throw401: Boolean = false): Future[Unit] = {
    authProvider.hasNotebookClusterPermission(userInfo, action, cluster.googleProject, cluster.clusterName) map {
      case false =>
        if( throw401 )
          throw AuthorizationError(Option(userInfo.userEmail))
        else
          throw ClusterNotFoundException(cluster.googleProject, cluster.clusterName)
      case true => ()
    }
  }

  def createCluster(userInfo: UserInfo, googleProject: GoogleProject, clusterName: ClusterName, clusterRequest: ClusterRequest): Future[Cluster] = {
    for {
      _ <- checkProjectPermission(userInfo, CreateClusters, googleProject)

      // Grab the service accounts from serviceAccountProvider for use later
      clusterServiceAccountOpt <- serviceAccountProvider.getClusterServiceAccount(userInfo, googleProject)
      notebookServiceAccountOpt <- serviceAccountProvider.getNotebookServiceAccount(userInfo, googleProject)
      serviceAccountInfo = ServiceAccountInfo(clusterServiceAccountOpt, notebookServiceAccountOpt)

      cluster <- internalCreateCluster(userInfo.userEmail, serviceAccountInfo, googleProject, clusterName, clusterRequest)
    } yield cluster
  }

  def internalCreateCluster(userEmail: WorkbenchEmail,
                            serviceAccountInfo: ServiceAccountInfo,
                            googleProject: GoogleProject,
                            clusterName: ClusterName,
                            clusterRequest: ClusterRequest): Future[Cluster] = {
    // Check if the google project has an active cluster with the same name. If not, we can create it
    dbRef.inTransaction { dataAccess =>
      dataAccess.clusterQuery.getActiveClusterByName(googleProject, clusterName)
    } flatMap {
      case Some(existingCluster) => throw ClusterAlreadyExistsException(googleProject, clusterName, existingCluster.status)
      case None =>
        val augmentedClusterRequest = addClusterDefaultLabels(serviceAccountInfo, googleProject, clusterName, userEmail, clusterRequest)
        val clusterFuture = for {
          // Notify the auth provider that the cluster has been created
          _ <- authProvider.notifyClusterCreated(userEmail, googleProject, clusterName)
          // Create the cluster in Google
          (cluster, initBucket, serviceAccountKeyOpt) <- createGoogleCluster(userEmail, serviceAccountInfo, googleProject, clusterName, augmentedClusterRequest)
          // Save the cluster in the database
          savedCluster <- dbRef.inTransaction(_.clusterQuery.save(cluster, GcsPath(initBucket, GcsObjectName("")), serviceAccountKeyOpt.map(_.id)))
        } yield {
          // Notify the cluster monitor that the cluster has been created
          clusterMonitorSupervisor ! ClusterCreated(savedCluster, clusterRequest.stopAfterCreation.getOrElse(false))
          savedCluster
        }

        // If cluster creation failed, createGoogleCluster removes resources in Google.
        // We also need to notify our auth provider that the cluster has been deleted.
        clusterFuture.andThen { case Failure(e) =>
          // Don't wait for this future
          authProvider.notifyClusterDeleted(userEmail, userEmail, googleProject, clusterName)
        }

        clusterFuture
    }
  }

  //throws 404 if nonexistent or no permissions
  def getActiveClusterDetails(userInfo: UserInfo, googleProject: GoogleProject, clusterName: ClusterName): Future[Cluster] = {
    for {
      cluster <- internalGetActiveClusterDetails(googleProject, clusterName) //throws 404 if nonexistent
      _ <- checkClusterPermission(userInfo, GetClusterStatus, cluster) //throws 404 if no auth
    } yield { cluster }
  }

  def internalGetActiveClusterDetails(googleProject: GoogleProject, clusterName: ClusterName): Future[Cluster] = {
    dbRef.inTransaction { dataAccess =>
      getActiveCluster(googleProject, clusterName, dataAccess)
    }
  }

  def deleteCluster(userInfo: UserInfo, googleProject: GoogleProject, clusterName: ClusterName): Future[Unit] = {
    for {
      //throws 404 if no permissions
      cluster <- getActiveClusterDetails(userInfo, googleProject, clusterName)

      //if you've got to here you at least have GetClusterDetails permissions so a 401 is appropriate if you can't actually destroy it
      _ <- checkClusterPermission(userInfo,  DeleteCluster, cluster, throw401 = true)

      _ <- internalDeleteCluster(userInfo.userEmail, cluster)
    } yield { () }
  }

  //NOTE: This function MUST ALWAYS complete ALL steps. i.e. if deleting thing1 fails, it must still proceed to delete thing2
  def internalDeleteCluster(userEmail: WorkbenchEmail, cluster: Cluster): Future[Unit] = {
    if (cluster.status.isDeletable) {
      for {
        // Delete the notebook service account key in Google, if present
        _ <- removeServiceAccountKey(cluster.googleProject, cluster.clusterName, cluster.serviceAccountInfo.notebookServiceAccount).recover { case NonFatal(e) =>
          logger.error(s"Error occurred removing service account key for ${cluster.googleProject} / ${cluster.clusterName}", e)
        }
        // Delete the cluster in Google
        _ <- gdDAO.deleteCluster(cluster.googleProject, cluster.clusterName)
        // Change the cluster status to Deleting in the database
        // Note this also changes the instance status to Deleting
        _ <- dbRef.inTransaction(dataAccess => dataAccess.clusterQuery.markPendingDeletion(cluster.googleId))
      } yield {
        // Notify the cluster monitor supervisor of cluster deletion.
        // This will kick off polling until the cluster is actually deleted in Google.
        clusterMonitorSupervisor ! ClusterDeleted(cluster.copy(status = ClusterStatus.Deleting))
      }
    } else Future.successful(())
  }

  def stopCluster(userInfo: UserInfo, googleProject: GoogleProject, clusterName: ClusterName): Future[Unit] = {
    for {
      //throws 404 if no permissions
      cluster <- getActiveClusterDetails(userInfo, googleProject, clusterName)

      //if you've got to here you at least have GetClusterDetails permissions so a 401 is appropriate if you can't actually stop it
      _ <- checkClusterPermission(userInfo, StopStartCluster, cluster, throw401 = true)

      _ <- internalStopCluster(cluster)
    } yield ()
  }

  def internalStopCluster(cluster: Cluster): Future[Unit] = {
    if (cluster.status.isStoppable) {
      for {
        // First remove all its preemptible instances in Google, if any
        _ <- if (cluster.machineConfig.numberOfPreemptibleWorkers.exists(_ > 0))
               gdDAO.resizeCluster(cluster.googleProject, cluster.clusterName, numPreemptibles = Some(0))
             else Future.successful(())

        // Now stop each instance individually
        _ <- Future.traverse(cluster.nonPreemptibleInstances) { instance =>
          // Install a startup script on the master node so Jupyter starts back up again once the instance is restarted
          instance.dataprocRole match {
            case Some(Master) =>
              googleComputeDAO.addInstanceMetadata(instance.key, masterInstanceStartupScript).flatMap { _ =>
                googleComputeDAO.stopInstance(instance.key)
              }
            case _ =>
              googleComputeDAO.stopInstance(instance.key)
          }
        }

        // Update the cluster status to Stopping
        _ <- dbRef.inTransaction { _.clusterQuery.setToStopping(cluster.googleId) }
      } yield {
        clusterMonitorSupervisor ! ClusterStopped(cluster.copy(status = ClusterStatus.Stopping))
      }

    } else Future.failed(ClusterCannotBeStoppedException(cluster.googleProject, cluster.clusterName, cluster.status))
  }

  def startCluster(userInfo: UserInfo, googleProject: GoogleProject, clusterName: ClusterName): Future[Unit] = {
    for {
      //throws 404 if no permissions
      cluster <- getActiveClusterDetails(userInfo, googleProject, clusterName)

      //if you've got to here you at least have GetClusterDetails permissions so a 401 is appropriate if you can't actually stop it
      _ <- checkClusterPermission(userInfo, StopStartCluster, cluster, throw401 = true)

      _ <- internalStartCluster(userInfo.userEmail, cluster)
    } yield ()
  }

  def internalStartCluster(userEmail: WorkbenchEmail, cluster: Cluster): Future[Unit] = {
    if (cluster.status.isStartable) {
      for {
        // Add back the preemptible instances
        _ <- if (cluster.machineConfig.numberOfPreemptibleWorkers.exists(_ > 0))
               gdDAO.resizeCluster(cluster.googleProject, cluster.clusterName, numPreemptibles = cluster.machineConfig.numberOfPreemptibleWorkers)
             else Future.successful(())

        // Start each instance individually
        _ <- Future.traverse(cluster.nonPreemptibleInstances) { instance =>
          googleComputeDAO.startInstance(instance.key)
        }

        // Update the cluster status to Starting
        _ <- dbRef.inTransaction { _.clusterQuery.updateClusterStatus(cluster.googleId, ClusterStatus.Starting) }
      } yield {
        clusterMonitorSupervisor ! ClusterStarted(cluster.copy(status = ClusterStatus.Starting))
      }

    } else Future.failed(ClusterCannotBeStartedException(cluster.googleProject, cluster.clusterName, cluster.status))
  }

  def listClusters(userInfo: UserInfo, params: LabelMap): Future[Seq[Cluster]] = {
    for {
      paramMap <- processListClustersParameters(params)
      clusterList <- dbRef.inTransaction { da => da.clusterQuery.listByLabels(paramMap._1, paramMap._2) }
      visibleClusters <- authProvider.filterUserVisibleClusters(userInfo, clusterList.map(c => (c.googleProject, c.clusterName)).toList)
    } yield {
      val visibleClustersSet = visibleClusters.toSet
      clusterList.filter(c => visibleClustersSet.contains((c.googleProject, c.clusterName)))
    }
  }

  private[service] def getActiveCluster(googleProject: GoogleProject, clusterName: ClusterName, dataAccess: DataAccess): DBIO[Cluster] = {
    dataAccess.clusterQuery.getActiveClusterByName(googleProject, clusterName) flatMap {
      case None => throw ClusterNotFoundException(googleProject, clusterName)
      case Some(cluster) => DBIO.successful(cluster)
    }
  }

  /* Creates a cluster in the given google project:
     - Add a firewall rule to the user's google project if it doesn't exist, so we can access the cluster
     - Create the initialization bucket for the cluster in the leo google project
     - Upload all the necessary initialization files to the bucket
     - Create the cluster in the google project */
  private[service] def createGoogleCluster(userEmail: WorkbenchEmail,
                                           serviceAccountInfo: ServiceAccountInfo,
                                           googleProject: GoogleProject,
                                           clusterName: ClusterName,
                                           clusterRequest: ClusterRequest)
                                          (implicit executionContext: ExecutionContext): Future[(Cluster, GcsBucketName, Option[ServiceAccountKey])] = {
    val initBucketName = generateUniqueBucketName("leoinit-"+clusterName.value)
    val stagingBucketName = generateUniqueBucketName("leostaging-"+clusterName.value)

    val googleFuture = for {

      // Validate that the Jupyter extension URIs and Jupyter user script URI are valid URIs and reference real GCS objects
      _ <- validateClusterRequestBucketObjectUri(userEmail, googleProject, clusterRequest)

      // Create the firewall rule in the google project if it doesn't already exist, so we can access the cluster
      _ <- googleComputeDAO.updateFirewallRule(googleProject, firewallRule)
      // Generate a service account key for the notebook service account (if present) to localize on the cluster.
      // We don't need to do this for the cluster service account because its credentials are already
      // on the metadata server.
      serviceAccountKeyOpt <- generateServiceAccountKey(googleProject, serviceAccountInfo.notebookServiceAccount)
      // Add Dataproc Worker role to the cluster service account, if present.
      // This is needed to be able to spin up Dataproc clusters.
      // If the Google Compute default service account is being used, this is not necessary.
      _ <- addDataprocWorkerRoleToServiceAccount(googleProject, serviceAccountInfo.clusterServiceAccount)
      // Create the bucket in leo's google project and populate with initialization files.
      // ACLs are granted so the cluster service account can access the bucket at initialization time.
      initBucket <- bucketHelper.createInitBucket(googleProject, initBucketName, serviceAccountInfo)
      _ <- initializeBucketObjects(userEmail, googleProject, clusterName, initBucket, clusterRequest, serviceAccountKeyOpt)
      // Create the cluster staging bucket. ACLs are granted so the user/pet can access it.
      stagingBucket <- bucketHelper.createStagingBucket(userEmail, googleProject, stagingBucketName, serviceAccountInfo)
      // Create the cluster
      machineConfig = MachineConfigOps.create(clusterRequest.machineConfig, clusterDefaultsConfig)
      initScript = GcsPath(initBucket, GcsObjectName(clusterResourcesConfig.initActionsScript.value))
      credentialsFileName = serviceAccountInfo.notebookServiceAccount.map(_ => s"/etc/${ClusterInitValues.serviceAccountCredentialsFilename}")
      cluster <- gdDAO.createCluster(googleProject, clusterName, machineConfig, initScript, serviceAccountInfo.clusterServiceAccount, credentialsFileName, stagingBucket).map { operation =>
        Cluster.create(clusterRequest, userEmail, clusterName, googleProject, operation, serviceAccountInfo, machineConfig, dataprocConfig.clusterUrlBase, stagingBucket)
      }
    } yield (cluster, initBucket, serviceAccountKeyOpt)

    // If anything fails, we need to clean up Google resources that might have been created
    googleFuture.andThen { case Failure(t) =>
      // Don't wait for this future
      cleanUpGoogleResourcesOnError(t, googleProject, clusterName, initBucketName, serviceAccountInfo)
    }
  }

  private[service] def cleanUpGoogleResourcesOnError(throwable: Throwable, googleProject: GoogleProject, clusterName: ClusterName, initBucketName: GcsBucketName, serviceAccountInfo: ServiceAccountInfo): Future[Unit] = {
    logger.error(s"Cluster creation failed in Google for ${googleProject.value} / ${clusterName.value}. Cleaning up resources in Google...")

    // Clean up resources in Google

    val deleteInitBucketFuture = leoGoogleStorageDAO.deleteBucket(initBucketName, recurse = true) map { _ =>
      logger.info(s"Successfully deleted init bucket ${initBucketName.value} for  ${googleProject.value} / ${clusterName.value}")
    } recover { case e =>
      logger.error(s"Failed to delete init bucket ${initBucketName.value} for  ${googleProject.value} / ${clusterName.value}", e)
    }

    // Don't delete the staging bucket so the user can see error logs.

    val deleteClusterFuture = gdDAO.deleteCluster(googleProject, clusterName) map { _ =>
      logger.info(s"Successfully deleted cluster ${googleProject.value} / ${clusterName.value}")
    } recover { case e =>
      logger.error(s"Failed to delete cluster ${googleProject.value} / ${clusterName.value}", e)
    }

    val deleteServiceAccountKeyFuture =  removeServiceAccountKey(googleProject, clusterName, serviceAccountInfo.notebookServiceAccount) map { _ =>
      logger.info(s"Successfully deleted service account key for ${serviceAccountInfo.notebookServiceAccount}")
    } recover { case e =>
      logger.error(s"Failed to delete service account key for ${serviceAccountInfo.notebookServiceAccount}", e)
    }

    Future.sequence(Seq(deleteInitBucketFuture, deleteClusterFuture, deleteServiceAccountKeyFuture)).void
  }

  private[service] def generateServiceAccountKey(googleProject: GoogleProject, serviceAccountOpt: Option[WorkbenchEmail]): Future[Option[ServiceAccountKey]] = {
    serviceAccountOpt.traverse { serviceAccountEmail =>
      googleIamDAO.createServiceAccountKey(googleProject, serviceAccountEmail)
    }
  }

  private[service] def removeServiceAccountKey(googleProject: GoogleProject, clusterName: ClusterName, serviceAccountOpt: Option[WorkbenchEmail]): Future[Unit] = {
    // Delete the service account key in Google, if present
    val tea = for {
      key <- OptionT(dbRef.inTransaction { _.clusterQuery.getServiceAccountKeyId(googleProject, clusterName) })
      serviceAccountEmail <- OptionT.fromOption[Future](serviceAccountOpt)
      _ <- OptionT.liftF(googleIamDAO.removeServiceAccountKey(googleProject, serviceAccountEmail, key))
    } yield ()

    tea.value.void
  }

  private[service] def addDataprocWorkerRoleToServiceAccount(googleProject: GoogleProject, serviceAccountOpt: Option[WorkbenchEmail]): Future[Unit] = {
    serviceAccountOpt.map { serviceAccountEmail =>
      googleIamDAO.addIamRolesForUser(googleProject, serviceAccountEmail, Set("roles/dataproc.worker"))
    } getOrElse Future.successful(())
  }


  private def validateClusterRequestBucketObjectUri(userEmail: WorkbenchEmail, googleProject: GoogleProject, clusterRequest: ClusterRequest)(implicit executionContext: ExecutionContext): Future[Unit] = {
    for {
      _ <- clusterRequest.jupyterUserScriptUri match {
        case Some(userScriptUri) => validateBucketObjectUri(userEmail, googleProject, userScriptUri.toUri)
        case None => Future.successful(())
      }

      _ <- clusterRequest.userJupyterExtensionConfig match {
        case Some(config) =>
          val extensionsToValidate = (config.nbExtensions.values ++ config.serverExtensions.values ++ config.combinedExtensions.values).filter(_.startsWith("gs://"))
          Future.traverse(extensionsToValidate)(x => validateBucketObjectUri(userEmail, googleProject, x))
        case None => Future.successful(())
      }
    } yield()
  }
  private[service] def validateBucketObjectUri(userEmail: WorkbenchEmail, googleProject: GoogleProject, gcsUri: String)(implicit executionContext: ExecutionContext): Future[Unit] = {

    val gcsUriOpt = parseGcsPath(gcsUri)
    gcsUriOpt match {
      case Right(gcsPath) =>
        if (gcsPath.toUri.length > bucketPathMaxLength)
          Future.failed(BucketObjectException(gcsUri))
        serviceAccountProvider.getAccessToken(userEmail, googleProject).flatMap {
          case Some(token) =>
            petGoogleStorageDAO(token).objectExists(gcsPath.bucketName, gcsPath.objectName).map {
              case true => ()
              case false => throw BucketObjectException(gcsPath.toUri)
            } recover {
              case e: HttpResponseException if e.getStatusCode == StatusCodes.Forbidden.intValue =>
                logger.error(s"User $userEmail does not have access to ${gcsPath.bucketName} / ${gcsPath.objectName}")
                throw BucketObjectAccessException(userEmail, gcsPath)
            }
          case None => Future.successful(())
        }
      case Left(err) => Future.failed(BucketObjectException(gcsUri))
    }
  }

  /* Process the templated cluster init script and put all initialization files in the init bucket */
  private[service] def initializeBucketObjects(userEmail: WorkbenchEmail, googleProject: GoogleProject, clusterName: ClusterName, initBucketName: GcsBucketName, clusterRequest: ClusterRequest, serviceAccountKey: Option[ServiceAccountKey]): Future[Unit] = {

    // Build a mapping of (name, value) pairs with which to apply templating logic to resources
    val replacements: Map[String, JsValue] = ClusterInitValues(googleProject, clusterName, initBucketName, clusterRequest, dataprocConfig,
      clusterFilesConfig, clusterResourcesConfig, proxyConfig, serviceAccountKey, userEmail
    ).toJson.asJsObject.fields

    // Raw files to upload to the bucket, no additional processing needed.
    val filesToUpload = List(
      clusterFilesConfig.jupyterServerCrt,
      clusterFilesConfig.jupyterServerKey,
      clusterFilesConfig.jupyterRootCaPem)

    // Raw resources to upload to the bucket, no additional processing needed.
    // Note: initActionsScript and jupyterGoogleSignInJs are not included
    // because they are post-processed by templating logic.
    val resourcesToUpload = List(
      clusterResourcesConfig.clusterDockerCompose,
      clusterResourcesConfig.jupyterProxySiteConf,
      clusterResourcesConfig.jupyterCustomJs)

    // Uploads the service account private key to the init bucket, if defined.
    // This is a no-op if createClusterAsPetServiceAccount is true.
    val uploadPrivateKeyFuture: Future[Unit] = serviceAccountKey.flatMap(_.privateKeyData.decode).map { k =>
      leoGoogleStorageDAO.storeObject(initBucketName, GcsObjectName(ClusterInitValues.serviceAccountCredentialsFilename), k, "text/plain")
    } getOrElse(Future.successful(()))

    // Fill in templated resources with the given replacements
    val initScriptContent = templateResource(clusterResourcesConfig.initActionsScript, replacements)
    val googleSignInJsContent = templateResource(clusterResourcesConfig.jupyterGoogleSignInJs, replacements)

    for {
      // Upload the init script to the bucket
      _ <- leoGoogleStorageDAO.storeObject(initBucketName, GcsObjectName(clusterResourcesConfig.initActionsScript.value), initScriptContent, "text/plain")

      // Upload the googleSignInJs file to the bucket
      _ <- leoGoogleStorageDAO.storeObject(initBucketName, GcsObjectName(clusterResourcesConfig.jupyterGoogleSignInJs.value), googleSignInJsContent, "text/plain")

      // Upload raw files (like certs) to the bucket
      _ <- Future.traverse(filesToUpload)(file => leoGoogleStorageDAO.storeObject(initBucketName, GcsObjectName(file.getName), file, "text/plain"))

      // Upload raw resources (like cluster-docker-compose.yml, site.conf) to the bucket
      _ <- Future.traverse(resourcesToUpload) { resource =>
        val content = Source.fromResource(s"${ClusterResourcesConfig.basePath}/${resource.value}").mkString
        leoGoogleStorageDAO.storeObject(initBucketName, GcsObjectName(resource.value), content, "text/plain")
      }

      // Update the private key json, if defined
      _ <- uploadPrivateKeyFuture
    } yield ()
  }

  /* Process a string using map of replacement values. Each value in the replacement map replaces it's key in the string. */
  private[service] def template(raw: String, replacementMap: Map[String, JsValue]): String = {
    replacementMap.foldLeft(raw)((a, b) => a.replaceAllLiterally("$(" + b._1 + ")", b._2.toString()))
  }

  private[service] def templateFile(file: File, replacementMap: Map[String, JsValue]): String = {
    val raw = Source.fromFile(file).mkString
    template(raw, replacementMap)
  }

  private[service] def templateResource(resource: ClusterResource, replacementMap: Map[String, JsValue]): String = {
    val raw = Source.fromResource(s"${ClusterResourcesConfig.basePath}/${resource.value}").mkString
    template(raw, replacementMap)
  }

  private[service] def processListClustersParameters(params: LabelMap): Future[(LabelMap, Boolean)] = {
    Future {
      params.get(includeDeletedKey) match {
        case Some(includeDeletedValue) => (processLabelMap(params - includeDeletedKey), includeDeletedValue.toBoolean)
        case None => (processLabelMap(params), false)
      }
    }
  }

  /**
    * There are 2 styles of passing labels to the list clusters endpoint:
    *
    * 1. As top-level query string parameters: GET /api/clusters?foo=bar&baz=biz
    * 2. Using the _labels query string parameter: GET /api/clusters?_labels=foo%3Dbar,baz%3Dbiz
    *
    * The latter style exists because Swagger doesn't provide a way to specify free-form query string
    * params. This method handles both styles, and returns a Map[String, String] representing the labels.
    *
    * Note that style 2 takes precedence: if _labels is present on the query string, any additional
    * parameters are ignored.
    *
    * @param params raw query string params
    * @return a Map[String, String] representing the labels
    */
  private[service] def processLabelMap(params: LabelMap): LabelMap = {
    params.get("_labels") match {
      case Some(extraLabels) =>
        extraLabels.split(',').foldLeft(Map.empty[String, String]) { (r, c) =>
          c.split('=') match {
            case Array(key, value) => r + (key -> value)
            case _ => throw ParseLabelsException(extraLabels)
          }
        }
      case None => params
    }
  }

  private[service] def addClusterDefaultLabels(serviceAccountInfo: ServiceAccountInfo, googleProject: GoogleProject, clusterName: ClusterName, creator: WorkbenchEmail, clusterRequest: ClusterRequest): ClusterRequest = {
    // create a LabelMap of default labels
    val defaultLabels = DefaultLabels(clusterName, googleProject, creator,
      serviceAccountInfo.clusterServiceAccount, serviceAccountInfo.notebookServiceAccount, clusterRequest.jupyterUserScriptUri)
      .toJson.asJsObject.fields.mapValues(labelValue => labelValue.convertTo[String])



    //Add UserJupyterUri to NbExtension
    val userJupyterExt = clusterRequest.jupyterExtensionUri match {
      case Some(ext) => Map[String, String]("notebookExtension" -> ext.toUri)
      case None => Map[String, String]()
    }

    val nbExtensions = userJupyterExt ++ clusterRequest.userJupyterExtensionConfig.map(_.nbExtensions).getOrElse(Map.empty)

    val serverExtensions = clusterRequest.userJupyterExtensionConfig.map(_.serverExtensions).getOrElse(Map.empty)

    val combinedExtension = clusterRequest.userJupyterExtensionConfig.map(_.combinedExtensions).getOrElse(Map.empty)

    // combine default and given labels and add labels for extensions
    val allLabels = clusterRequest.labels ++ defaultLabels ++ nbExtensions ++ serverExtensions ++ combinedExtension

    val updatedUserJupyterExtensionConfig = if(nbExtensions.isEmpty && serverExtensions.isEmpty && combinedExtension.isEmpty) None else Some(UserJupyterExtensionConfig(nbExtensions, serverExtensions, combinedExtension))
    // check the labels do not contain forbidden keys
    if (allLabels.contains(includeDeletedKey))
      throw IllegalLabelKeyException(includeDeletedKey)
    else clusterRequest.copy(labels = allLabels).copy(userJupyterExtensionConfig = updatedUserJupyterExtensionConfig)
  }
}
