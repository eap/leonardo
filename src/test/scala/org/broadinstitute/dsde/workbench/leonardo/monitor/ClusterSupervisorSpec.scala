package org.broadinstitute.dsde.workbench.leonardo.monitor

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleStorageDAO
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.leonardo.dao.MockJupyterDAO
import org.broadinstitute.dsde.workbench.leonardo.dao.google.{GoogleComputeDAO, GoogleDataprocDAO}
import org.broadinstitute.dsde.workbench.leonardo.{CommonTestData, GcsPathUtils}
import org.broadinstitute.dsde.workbench.leonardo.db.{DbSingleton, TestComponent}
import org.broadinstitute.dsde.workbench.leonardo.model.google.{ClusterStatus, MachineConfig, OperationName}
import org.broadinstitute.dsde.workbench.leonardo.model.{Cluster, LeoAuthProvider, ServiceAccountInfo}
import org.broadinstitute.dsde.workbench.leonardo.service.LeonardoService
import org.broadinstitute.dsde.workbench.leonardo.util.BucketHelper
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Seconds, Span}
import org.scalatest.concurrent.Eventually.eventually

class ClusterSupervisorSpec extends TestKit(ActorSystem("leonardotest")) with FlatSpecLike with Matchers with MockitoSugar with BeforeAndAfterAll with TestComponent with CommonTestData with GcsPathUtils {
  testKit =>

  val runningCluster = Cluster(
    clusterName = name1,
    googleId = UUID.randomUUID(),
    googleProject = project,
    serviceAccountInfo = ServiceAccountInfo(clusterServiceAccount(project), notebookServiceAccount(project)),
    machineConfig = MachineConfig(Some(0), Some(""), Some(500)),
    clusterUrl = Cluster.getClusterUrl(project, name1, clusterUrlBase),
    operationName = OperationName("op1"),
    status = ClusterStatus.Running,
    hostIp = None,
    creator = userEmail,
    createdDate = Instant.now(),
    destroyedDate = None,
    labels = Map("bam" -> "yes", "vcf" -> "no"),
    None,
    None,
    Some(GcsBucketName("testStagingBucket1")),
    List.empty,
    Set.empty,
    Some(userExtConfig),
    Instant.now()
  )

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
  //TODO: Remove ignore once auto freeze is enabled
  "ClusterSupervisorMonitor" should "auto freeze the cluster" ignore isolatedDbTest {

    val gdDAO = mock[GoogleDataprocDAO]

    val computeDAO = mock[GoogleComputeDAO]

    val storageDAO = mock[GoogleStorageDAO]

    val iamDAO = mock[GoogleIamDAO]

    val authProvider = mock[LeoAuthProvider]

    val jupyterProxyDAO = new MockJupyterDAO

    val mockPetGoogleStorageDAO: String => GoogleStorageDAO = _ => {
      new MockGoogleStorageDAO
    }

    val bucketHelper = new BucketHelper(dataprocConfig, gdDAO, computeDAO, storageDAO, serviceAccountProvider)
    dbFutureValue {
      _.clusterQuery.save(runningCluster, gcsPath("gs://bucket"), Some(serviceAccountKey.id))
    } shouldEqual runningCluster
    val clusterSupervisorActor = system.actorOf(ClusterMonitorSupervisor.props(monitorConfig, dataprocConfig, gdDAO, computeDAO, iamDAO, storageDAO,
      DbSingleton.ref, system.deadLetters, authProvider, autoFreezeconfig, jupyterProxyDAO))
    new LeonardoService(dataprocConfig, clusterFilesConfig, clusterResourcesConfig, clusterDefaultsConfig, proxyConfig, swaggerConfig, gdDAO, computeDAO, iamDAO, storageDAO, mockPetGoogleStorageDAO, DbSingleton.ref, clusterSupervisorActor, whitelistAuthProvider, serviceAccountProvider, whitelist, bucketHelper)

    eventually(timeout(Span(30, Seconds))) {
      val c1 = dbFutureValue {
        _.clusterQuery.getByGoogleId(runningCluster.googleId)
      }
      c1.map(_.status).get shouldBe ClusterStatus.Stopping
    }
  }

}
