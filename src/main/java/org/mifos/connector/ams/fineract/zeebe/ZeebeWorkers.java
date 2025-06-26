package org.mifos.connector.ams.fineract.zeebe;

import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.AMS_PAYMENT_RESPONSE;
import static org.mifos.connector.ams.fineract.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.CONFIRMATION_RECEIVED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.CUSTOM_DATA;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_CODE;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_DESCRIPTION;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.EXTERNAL_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.GET_TRANSACTION_STATUS_RESPONSE_CODE;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.TRANSFER_SETTLEMENT_FAILED;
import static org.mifos.connector.ams.fineract.zeebe.ZeebeVariables.VALIDATION_AND_SETTLEMENT_WORKER_NAME;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mifos.connector.ams.fineract.data.ChannelRequest;
import org.mifos.connector.ams.fineract.data.FineractPaymentResponse;
import org.mifos.connector.ams.fineract.util.ConnectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Contains workers that will run based on the BPMN flow. */
@Slf4j
@Component
public class ZeebeWorkers {

    private final ZeebeClient zeebeClient;
    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper mapper;

    // This value determines if an API call to Fineract AMS will be made
    @Value("${ams.local.enabled:false}")
    private boolean isAmsLocalEnabled;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    public ZeebeWorkers(ZeebeClient zeebeClient, CamelContext camelContext, ProducerTemplate producerTemplate,
            ObjectMapper mapper) {
        this.zeebeClient = zeebeClient;
        this.camelContext = camelContext;
        this.producerTemplate = producerTemplate;
        this.mapper = mapper;
    }

    /** Defining workers in charge of calling Fineract validation and confirmation APIs. */
    @PostConstruct
    public void setupWorkers() {
        // Defining worker in charge of calling Fineract validation API
        zeebeClient.newWorker().jobType(FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME).handler((client, job) -> {
            logWorkerDetails(job);

            Map<String, Object> variables;

            if (isAmsLocalEnabled) {
                Exchange ex = new DefaultExchange(camelContext);

                variables = job.getVariablesAsMap();

                JSONObject channelRequest = new JSONObject((String) variables.get(CHANNEL_REQUEST));
                ex.setProperty(CHANNEL_REQUEST, channelRequest);
                ex.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                String customDataString = (String) variables.get(CUSTOM_DATA);
                if (customDataString != null && !customDataString.isBlank()) {
                    JSONArray customData = new JSONArray(customDataString);
                    ex.setProperty(CUSTOM_DATA, customData);
                }
                producerTemplate.send("direct:transfer-validation-base", ex);

                checkOperationResultAndSetVariables(PARTY_LOOKUP_FAILED, ex, variables);

            } else {
                variables = setVariablesForDisabledLocalAms(PARTY_LOOKUP_FAILED);
            }
            zeebeClient.newCompleteCommand(job.getKey()).variables(variables).send();
        }).name(FINERACT_AMS_ZEEBEE_VALIDATION_WORKER_NAME).maxJobsActive(workerMaxJobs).open();

        // Defining worker in charge of calling Fineract confirmation API
        zeebeClient.newWorker().jobType(FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME).handler((client, job) -> {
            logWorkerDetails(job);

            Map<String, Object> variables;

            if (isAmsLocalEnabled) {
                Exchange ex = new DefaultExchange(camelContext);
                variables = job.getVariablesAsMap();
                JSONObject channelRequest = new JSONObject((String) variables.get(CHANNEL_REQUEST));
                ex.setProperty(CHANNEL_REQUEST, channelRequest);
                ex.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                ex.setProperty(EXTERNAL_ID, variables.get(EXTERNAL_ID));
                ex.setProperty(TRANSACTION_FAILED, variables.get(TRANSACTION_FAILED));
                ex.setProperty(ERROR_DESCRIPTION, variables.get(ERROR_DESCRIPTION));
                ex.setProperty(GET_TRANSACTION_STATUS_RESPONSE_CODE,
                        variables.get(GET_TRANSACTION_STATUS_RESPONSE_CODE));
                ex.setProperty(CONFIRMATION_RECEIVED, variables.get(CONFIRMATION_RECEIVED));

                producerTemplate.send("direct:transfer-settlement-base", ex);

                checkOperationResultAndSetVariables(TRANSFER_SETTLEMENT_FAILED, ex, variables);
            } else {
                variables = setVariablesForDisabledLocalAms(TRANSFER_SETTLEMENT_FAILED);
            }

            zeebeClient.newCompleteCommand(job.getKey()).variables(variables).send();
        }).name(FINERACT_AMS_ZEEBEE_SETTLEMENT_WORKER_NAME).maxJobsActive(workerMaxJobs).open();

        zeebeClient.newWorker().jobType(VALIDATION_AND_SETTLEMENT_WORKER_NAME).handler((client, job) -> {

            logWorkerDetails(job);
            Map<String, Object> variables;
            if (isAmsLocalEnabled) {
                Exchange ex = new DefaultExchange(camelContext);
                variables = job.getVariablesAsMap();
                ex.setProperty(CHANNEL_REQUEST, variables.get(CHANNEL_REQUEST));
                ex.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                ex.setProperty(EXTERNAL_ID, variables.get(EXTERNAL_ID));
                producerTemplate.send("direct:transfer-validation-and-settlement-base", ex);
                checkOperationResultAndSetVariables(TRANSFER_SETTLEMENT_FAILED, ex, variables);
                String amsResponse = ex.getProperty(AMS_PAYMENT_RESPONSE, String.class);
                FineractPaymentResponse paymentResponse = mapper.readValue(amsResponse, FineractPaymentResponse.class);
                if (paymentResponse != null && paymentResponse.accountNumber() != null) {
                    ChannelRequest channelRequest = ex.getProperty(CHANNEL_REQUEST, ChannelRequest.class);
                    channelRequest.getPayee().getPartyIdInfo().setPartyIdentifier(paymentResponse.accountNumber());
                    variables.put(CHANNEL_REQUEST, mapper.writeValueAsString(channelRequest));
                }
                variables.put(AMS_PAYMENT_RESPONSE, amsResponse);
            } else {
                variables = setVariablesForDisabledLocalAms(TRANSFER_SETTLEMENT_FAILED);
            }

            zeebeClient.newCompleteCommand(job.getKey()).variables(variables).send();
        }).name(VALIDATION_AND_SETTLEMENT_WORKER_NAME).maxJobsActive(workerMaxJobs).open();
    }

    private void logWorkerDetails(ActivatedJob job) {
        JSONObject jsonJob = new JSONObject();
        jsonJob.put("bpmnProcessId", job.getBpmnProcessId());
        jsonJob.put("elementInstanceKey", job.getElementInstanceKey());
        jsonJob.put("jobKey", job.getKey());
        jsonJob.put("jobType", job.getType());
        jsonJob.put("workflowElementId", job.getElementId());
        jsonJob.put("workflowDefinitionVersion", job.getProcessDefinitionVersion());
        jsonJob.put("workflowKey", job.getProcessDefinitionKey());
        jsonJob.put("workflowInstanceKey", job.getProcessInstanceKey());
        log.info("Job started: {}", jsonJob.toString(4));
    }

    /**
     * Set variables values when the AMS is disalbled.
     *
     * @param operationName
     *            the operation name
     * @return a map of variables with their values
     */
    private Map<String, Object> setVariablesForDisabledLocalAms(String operationName) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(operationName, false);
        variables.put(ERROR_INFORMATION, "AMS Local is disabled");
        variables.put(ERROR_CODE, null);
        variables.put(ERROR_DESCRIPTION, "AMS Local is disabled");
        return variables;
    }

    /**
     * Check result of the API call and set variables in Zeebe accordingly.
     *
     * @param operationName
     *            the operation name
     * @param ex
     *            {@link Exchange}
     * @param variables
     *            a map of existing variables to be updated
     */
    private void checkOperationResultAndSetVariables(String operationName, Exchange ex, Map<String, Object> variables) {
        Boolean isOperationFailed = ex.getProperty(operationName, boolean.class);

        variables.put(operationName, isOperationFailed);
        if (isOperationFailed == null || isOperationFailed) {
            variables.put(operationName, true);
            variables.put(ERROR_INFORMATION, ex.getIn().getBody(String.class));
            variables.put(ERROR_CODE, ex.getIn().getHeader("CamelHttpResponseCode"));
            variables.put(ERROR_DESCRIPTION,
                    ConnectionUtils.parseErrorDescriptionFromJsonPayload(ex.getIn().getBody(String.class)));
        }
    }
}
