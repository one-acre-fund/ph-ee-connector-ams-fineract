package org.mifos.connector.ams.fineract.zeebe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_DESCRIPTION;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSFER_SETTLEMENT_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.VALIDATION_AND_SETTLEMENT_WORKER_NAME;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.command.CompleteJobCommandStep1;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.CompleteJobResponse;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ZeebeWorkersTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long JOB_KEY = 99L;
    private static final int WORKER_MAX_JOBS = 20;

    @Mock
    private ZeebeClient zeebeClient;

    @Mock
    private ProducerTemplate producerTemplate;

    @Mock
    private JobClient jobClient;

    @Mock
    private ActivatedJob job;

    @Mock
    private JobWorkerBuilderStep1 workerBuilderStep1;

    @Mock
    private JobWorkerBuilderStep2 workerBuilderStep2;

    @Mock
    private JobWorkerBuilderStep3 workerBuilderStep3;

    @Mock
    private JobWorker jobWorker;

    @Mock
    private CompleteJobCommandStep1 completeCommand;

    private CamelContext camelContext;
    private final Map<String, JobHandler> handlersByJobType = new HashMap<>();
    private ZeebeFuture<CompleteJobResponse> completeFuture;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        camelContext = new DefaultCamelContext();
        handlersByJobType.clear();
        completeFuture = mock(ZeebeFuture.class);
    }

    @AfterEach
    void tearDown() {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    @Test
    void setupWorkers_registersAllThreeWorkersWithMaxJobsActive() {
        stubWorkerRegistration();

        ZeebeWorkers workers = newWorkers(false);
        workers.setupWorkers();

        assertTrue(handlersByJobType.containsKey(FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME));
        assertTrue(handlersByJobType.containsKey(FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME));
        assertTrue(handlersByJobType.containsKey(VALIDATION_AND_SETTLEMENT_WORKER_NAME));
        verify(workerBuilderStep3, times(3)).maxJobsActive(WORKER_MAX_JOBS);
        verify(workerBuilderStep3, times(3)).open();
    }

    @Test
    void validationWorker_whenAmsDisabled_completesWithBoundedJoin() throws Exception {
        stubWorkerRegistration();
        stubSuccessfulComplete();
        stubJobMetadata(FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME);

        ZeebeWorkers workers = newWorkers(false);
        workers.setupWorkers();

        handlersByJobType.get(FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME).handle(jobClient, job);

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completeCommand).variables(variablesCaptor.capture());
        Map<String, Object> variables = variablesCaptor.getValue();
        assertFalse((Boolean) variables.get(PARTY_LOOKUP_FAILED));
        assertEquals("AMS Local is disabled", variables.get(ERROR_INFORMATION));
        assertEquals("AMS Local is disabled", variables.get(ERROR_DESCRIPTION));
        verify(completeFuture).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void settlementWorker_whenAmsDisabled_completesWithBoundedJoin() throws Exception {
        stubWorkerRegistration();
        stubSuccessfulComplete();
        stubJobMetadata(FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME);

        ZeebeWorkers workers = newWorkers(false);
        workers.setupWorkers();

        handlersByJobType.get(FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME).handle(jobClient, job);

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completeCommand).variables(variablesCaptor.capture());
        Map<String, Object> variables = variablesCaptor.getValue();
        assertFalse((Boolean) variables.get(TRANSFER_SETTLEMENT_FAILED));
        assertEquals("AMS Local is disabled", variables.get(ERROR_INFORMATION));
        verify(completeFuture).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void validationAndSettlementWorker_whenAmsDisabled_completesWithBoundedJoin() throws Exception {
        stubWorkerRegistration();
        stubSuccessfulComplete();
        stubJobMetadata(VALIDATION_AND_SETTLEMENT_WORKER_NAME);

        ZeebeWorkers workers = newWorkers(false);
        workers.setupWorkers();

        handlersByJobType.get(VALIDATION_AND_SETTLEMENT_WORKER_NAME).handle(jobClient, job);

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completeCommand).variables(variablesCaptor.capture());
        Map<String, Object> variables = variablesCaptor.getValue();
        assertFalse((Boolean) variables.get(TRANSFER_SETTLEMENT_FAILED));
        assertEquals("AMS Local is disabled", variables.get(ERROR_INFORMATION));
        verify(completeFuture).join(30_000L, TimeUnit.MILLISECONDS);
    }

    private ZeebeWorkers newWorkers(boolean amsLocalEnabled) {
        ZeebeWorkers workers = new ZeebeWorkers(zeebeClient, camelContext, producerTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(workers, "isAmsLocalEnabled", amsLocalEnabled);
        ReflectionTestUtils.setField(workers, "workerMaxJobs", WORKER_MAX_JOBS);
        ReflectionTestUtils.setField(workers, "requestTimeout", REQUEST_TIMEOUT);
        return workers;
    }

    private void stubWorkerRegistration() {
        ArgumentCaptor<String> jobTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JobHandler> handlerCaptor = ArgumentCaptor.forClass(JobHandler.class);

        when(zeebeClient.newWorker()).thenReturn(workerBuilderStep1);
        when(workerBuilderStep1.jobType(jobTypeCaptor.capture())).thenReturn(workerBuilderStep2);
        when(workerBuilderStep2.handler(handlerCaptor.capture())).thenAnswer(invocation -> {
            String jobType = jobTypeCaptor.getAllValues().get(jobTypeCaptor.getAllValues().size() - 1);
            handlersByJobType.put(jobType, invocation.getArgument(0));
            return workerBuilderStep3;
        });
        when(workerBuilderStep3.name(anyString())).thenReturn(workerBuilderStep3);
        when(workerBuilderStep3.maxJobsActive(WORKER_MAX_JOBS)).thenReturn(workerBuilderStep3);
        when(workerBuilderStep3.open()).thenReturn(jobWorker);
    }

    private void stubSuccessfulComplete() {
        when(job.getKey()).thenReturn(JOB_KEY);
        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.variables(anyMap())).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(completeFuture);
        when(completeFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(CompleteJobResponse.class));
    }

    private void stubJobMetadata(String jobType) {
        when(job.getBpmnProcessId()).thenReturn("mpesa_flow_fineract-kenya");
        when(job.getElementInstanceKey()).thenReturn(1L);
        when(job.getType()).thenReturn(jobType);
        when(job.getElementId()).thenReturn(jobType);
        when(job.getProcessDefinitionVersion()).thenReturn(1);
        when(job.getProcessDefinitionKey()).thenReturn(2L);
        when(job.getProcessInstanceKey()).thenReturn(3L);
    }
}
