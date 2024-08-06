// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.codewhispererruntime.model.AccessDeniedException
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationProgressUpdate
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.amazon.awssdk.services.ssooidc.model.InvalidGrantException
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getBillingText
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getTableMapping
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.pollTransformationStatusAndPlan
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.refreshToken
import java.util.concurrent.atomic.AtomicBoolean

class CodeWhispererCodeModernizerUtilsTest : CodeWhispererCodeModernizerTestBase() {
    @Before
    override fun setup() {
        super.setup()
    }

    @Test
    fun `can poll for updates`() {
        Mockito.doReturn(
            exampleGetCodeMigrationResponse,
            exampleGetCodeMigrationResponse.replace(TransformationStatus.TRANSFORMING),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.STARTED),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.COMPLETED), // Should stop before this point
        )
            .whenever(clientAdaptorSpy).getCodeModernizationJob(any())
        Mockito.doReturn(exampleGetCodeMigrationPlanResponse)
            .whenever(clientAdaptorSpy).getCodeModernizationPlan(any())
        val mutableList = mutableListOf<TransformationStatus>()
        runBlocking {
            jobId.pollTransformationStatusAndPlan(
                setOf(TransformationStatus.STARTED),
                setOf(TransformationStatus.FAILED),
                clientAdaptorSpy,
                0,
                0,
                AtomicBoolean(false),
                project
            ) { _, status, _ ->
                mutableList.add(status)
            }
        }
        val expected =
            listOf<TransformationStatus>(
                exampleGetCodeMigrationResponse.transformationJob().status(),
                TransformationStatus.TRANSFORMING,
                TransformationStatus.STARTED,
            )
        assertThat(expected).isEqualTo(mutableList)
    }

    @Test
    fun `refresh on access denied`() {
        val mockAccessDeniedException = Mockito.mock(AccessDeniedException::class.java)

        mockkStatic(::refreshToken)
        every { refreshToken(any()) } just runs

        Mockito.doThrow(
            mockAccessDeniedException
        ).doReturn(
            exampleGetCodeMigrationResponse,
            exampleGetCodeMigrationResponse.replace(TransformationStatus.STARTED),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.COMPLETED), // Should stop before this point
        ).whenever(clientAdaptorSpy).getCodeModernizationJob(any())

        Mockito.doReturn(exampleGetCodeMigrationPlanResponse)
            .whenever(clientAdaptorSpy).getCodeModernizationPlan(any())

        val mutableList = mutableListOf<TransformationStatus>()
        runBlocking {
            jobId.pollTransformationStatusAndPlan(
                setOf(TransformationStatus.STARTED),
                setOf(TransformationStatus.FAILED),
                clientAdaptorSpy,
                0,
                0,
                AtomicBoolean(false),
                project
            ) { _, status, _ ->
                mutableList.add(status)
            }
        }
        val expected =
            listOf<TransformationStatus>(
                exampleGetCodeMigrationResponse.transformationJob().status(),
                TransformationStatus.STARTED,
            )
        assertThat(expected).isEqualTo(mutableList)
        io.mockk.verify { refreshToken(any()) }
    }

    @Test
    fun `refresh on invalid grant`() {
        val mockInvalidGrantException = Mockito.mock(InvalidGrantException::class.java)

        mockkStatic(::refreshToken)
        every { refreshToken(any()) } just runs

        Mockito.doThrow(
            mockInvalidGrantException
        ).doReturn(
            exampleGetCodeMigrationResponse,
            exampleGetCodeMigrationResponse.replace(TransformationStatus.STARTED),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.COMPLETED), // Should stop before this point
        ).whenever(clientAdaptorSpy).getCodeModernizationJob(any())

        Mockito.doReturn(exampleGetCodeMigrationPlanResponse)
            .whenever(clientAdaptorSpy).getCodeModernizationPlan(any())

        val mutableList = mutableListOf<TransformationStatus>()
        runBlocking {
            jobId.pollTransformationStatusAndPlan(
                setOf(TransformationStatus.STARTED),
                setOf(TransformationStatus.FAILED),
                clientAdaptorSpy,
                0,
                0,
                AtomicBoolean(false),
                project
            ) { _, status, _ ->
                mutableList.add(status)
            }
        }
        val expected =
            listOf<TransformationStatus>(
                exampleGetCodeMigrationResponse.transformationJob().status(),
                TransformationStatus.STARTED,
            )
        assertThat(expected).isEqualTo(mutableList)
        io.mockk.verify { refreshToken(any()) }
    }

    @Test
    fun `stops polling when status transitions to failOn`() {
        Mockito.doReturn(
            exampleGetCodeMigrationResponse,
            exampleGetCodeMigrationResponse.replace(TransformationStatus.FAILED),
            *happyPathMigrationResponses.toTypedArray(), // These should never be passed through the client
        )
            .whenever(clientAdaptorSpy).getCodeModernizationJob(any())
        val mutableList = mutableListOf<TransformationStatus>()

        val result = runBlocking {
            jobId.pollTransformationStatusAndPlan(
                setOf(TransformationStatus.COMPLETED),
                setOf(TransformationStatus.FAILED),
                clientAdaptorSpy,
                0,
                0,
                AtomicBoolean(false),
                project,
            ) { _, status, _ ->
                mutableList.add(status)
            }
        }
        assertThat(result.succeeded).isFalse()
        val expected = listOf<TransformationStatus>(
            exampleGetCodeMigrationResponse.transformationJob().status(),
            TransformationStatus.FAILED,
        )
        assertThat(expected).isEqualTo(mutableList)
        verify(clientAdaptorSpy, times(2)).getCodeModernizationJob(any())
    }

    @Test
    fun `getTableMapping on complete step 0 progressUpdates creates map correctly`() {
        val jobStats =
            """{"name":"Job statistics", "columnNames":["name","value"],"rows":[{"name":"Dependencies to be replaced","value":"5"},
                |{"name":"Deprecated code instances to be replaced","value":"10"}]}"""
                .trimMargin()
        val depChanges =
            """{"name":"Dependency changes", "columnNames":["dependencyName","action","currentVersion","targetVersion"],
                |"rows":[{"dependencyName":"org.springboot.com","action":"Update","currentVersion":"2.1","targetVersion":"2.4"}]}"""
                .trimMargin()
        val apiChanges =
            """{"name":"Deprecated API changes", "columnNames":["apiFullyQualifiedName","numChangedFiles"],
                |"rows":[{"apiFullyQualifiedName": "java.lang.bad()", "numChangedFiles": "3"}]}"""
                .trimMargin()
        val fileChanges =
            """{"name":"File changes", "columnNames":["relativePath","action"],"rows":[{"relativePath":"pom.xml","action":"Update"}, 
                |{"relativePath":"src/main/java/BloodbankApplication.java","action":"Update"}]}"""
                .trimMargin()
        val step0Update0 = TransformationProgressUpdate.builder().name("0").status("COMPLETED").description(jobStats).build()
        val step0Update1 = TransformationProgressUpdate.builder().name("1").status("COMPLETED").description(depChanges).build()
        val step0Update2 = TransformationProgressUpdate.builder().name("2").status("COMPLETED").description(apiChanges).build()
        val step0Update3 = TransformationProgressUpdate.builder().name("-1").status("COMPLETED").description(fileChanges).build()
        val actual = getTableMapping(listOf(step0Update0, step0Update1, step0Update2, step0Update3))
        val expected = mapOf("0" to jobStats, "1" to depChanges, "2" to apiChanges, "-1" to fileChanges)
        assertThat(expected).isEqualTo(actual)
    }

    @Test
    fun `getBillingText on small project returns correct String`() {
        val expected = "<html><body style=\"line-height:2; font-family: Arial, sans-serif; font-size: 14;\"><br>" +
            "376 lines of code submitted for transformation, maximum charge of this transformation is 376 * $0.003 = $1.13" +
            " (for latest pricing, see https://aws.amazon.com/q/developer/pricing/). This charge applies only after the free " +
            "limit in your organization's subscriptions is exhausted. To prevent the charge, you can stop the job before the " +
            "transformation completes.<br></body></html>"
        val actual = getBillingText(376)
        assertThat(expected).isEqualTo(actual)
    }
}
