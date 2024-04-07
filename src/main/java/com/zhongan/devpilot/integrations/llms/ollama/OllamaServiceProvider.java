package com.zhongan.devpilot.integrations.llms.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zhongan.devpilot.gui.toolwindows.chat.DevPilotChatToolWindowService;
import com.zhongan.devpilot.integrations.llms.LlmProvider;
import com.zhongan.devpilot.integrations.llms.entity.DevPilotChatCompletionRequest;
import com.zhongan.devpilot.integrations.llms.entity.DevPilotChatCompletionResponse;
import com.zhongan.devpilot.integrations.llms.entity.DevPilotFailedResponse;
import com.zhongan.devpilot.integrations.llms.entity.DevPilotMessage;
import com.zhongan.devpilot.integrations.llms.entity.DevPilotSuccessResponse;
import com.zhongan.devpilot.integrations.llms.entity.OllamaModelListResponse;
import com.zhongan.devpilot.settings.state.OllamaSettingsState;
import com.zhongan.devpilot.util.DevPilotMessageBundle;
import com.zhongan.devpilot.util.JsonUtils;
import com.zhongan.devpilot.util.OkhttpUtils;
import com.zhongan.devpilot.util.UserAgentUtils;
import com.zhongan.devpilot.webview.model.MessageModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.sse.EventSource;

@Service(Service.Level.PROJECT)
public final class OllamaServiceProvider implements LlmProvider {
    private static final Logger log = Logger.getInstance(OllamaServiceProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventSource es;

    private DevPilotChatToolWindowService toolWindowService;

    private MessageModel resultModel = new MessageModel();

    @Override
    public String chatCompletion(Project project, DevPilotChatCompletionRequest chatCompletionRequest, Consumer<String> callback) {
        var host = OllamaSettingsState.getInstance().getModelHost();
        var modelName = OllamaSettingsState.getInstance().getModelName();
        var service = project.getService(DevPilotChatToolWindowService.class);
        this.toolWindowService = service;

        if (StringUtils.isEmpty(host)) {
            service.callErrorInfo("Chat completion failed: host is empty");
            return "";
        }

        if (StringUtils.isEmpty(modelName)) {
            service.callErrorInfo("Chat completion failed: ollama model name is empty");
            return "";
        }

        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }

        chatCompletionRequest.setModel(modelName);

        try {
            var request = new Request.Builder()
                .url(host + "/v1/chat/completions")
                .header("User-Agent", UserAgentUtils.getUserAgent())
                .post(RequestBody.create(objectMapper.writeValueAsString(chatCompletionRequest), MediaType.parse("application/json")))
                .build();

            this.es = this.buildEventSource(request, service, callback);
        } catch (Exception e) {
            service.callErrorInfo("Chat completion failed: " + e.getMessage());
            return "";
        }

        return "";
    }

    @Override
    public void interruptSend() {
        if (es != null) {
            es.cancel();
            // remember the broken message
            if (resultModel != null && !StringUtils.isEmpty(resultModel.getContent())) {
                resultModel.setStreaming(false);
                toolWindowService.addMessage(resultModel);
            }

            toolWindowService.callWebView();
            // after interrupt, reset result model
            resultModel = null;
        }
    }

    @Override
    public List<String> listModels(String host, String apiKey) {
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        List<String> modelList = new ArrayList<>();
        try {
            var request = new Request.Builder()
                .get()
                .url(host + "/api/tags")
                .build();
            Call call = OkhttpUtils.getClient().newCall(request);
            okhttp3.Response response = call.execute();
            if (response.isSuccessful()) {
                var result = Objects.requireNonNull(response.body()).string();
                var modelListResponse = JsonUtils.fromJson(result, OllamaModelListResponse.class);
                if (modelListResponse != null) {
                    for (OllamaModelListResponse.Model model : modelListResponse.getModels()) {
                        modelList.add(model.getName());
                    }
                }
            }
            response.close();
        } catch (Exception ex) {
            log.error("ollama list models error", ex);
        }
        return modelList;
    }

    @Override
    public DevPilotChatCompletionResponse chatCompletionSync(DevPilotChatCompletionRequest chatCompletionRequest) {
        var host = OllamaSettingsState.getInstance().getModelHost();
        var modelName = OllamaSettingsState.getInstance().getModelName();

        if (StringUtils.isEmpty(host)) {
            return DevPilotChatCompletionResponse.failed("Chat completion failed: host is empty");
        }

        if (StringUtils.isEmpty(modelName)) {
            return DevPilotChatCompletionResponse.failed("Chat completion failed: ollama model name is empty");
        }

        chatCompletionRequest.setModel(modelName);

        okhttp3.Response response;

        try {
            var request = new Request.Builder()
                .url(host + "/v1/chat/completions")
                .header("User-Agent", UserAgentUtils.getUserAgent())
                .post(RequestBody.create(objectMapper.writeValueAsString(chatCompletionRequest), MediaType.parse("application/json")))
                .build();

            Call call = OkhttpUtils.getClient().newCall(request);
            response = call.execute();
        } catch (Exception e) {
            return DevPilotChatCompletionResponse.failed("Chat completion failed: " + e.getMessage());
        }

        try {
            return parseResult(chatCompletionRequest, response);
        } catch (IOException e) {
            return DevPilotChatCompletionResponse.failed("Chat completion failed: " + e.getMessage());
        }
    }

    private DevPilotChatCompletionResponse parseResult(DevPilotChatCompletionRequest chatCompletionRequest, okhttp3.Response response) throws IOException {
        if (response == null) {
            return DevPilotChatCompletionResponse.failed(DevPilotMessageBundle.get("devpilot.chatWindow.response.null"));
        }

        var result = Objects.requireNonNull(response.body()).string();

        if (response.isSuccessful()) {
            var message = objectMapper.readValue(result, DevPilotSuccessResponse.class)
                .getChoices()
                .get(0)
                .getMessage();
            var devPilotMessage = new DevPilotMessage();
            devPilotMessage.setRole("assistant");
            devPilotMessage.setContent(message.getContent());
            chatCompletionRequest.getMessages().add(devPilotMessage);
            return DevPilotChatCompletionResponse.success(message.getContent());

        } else {
            return DevPilotChatCompletionResponse.failed(objectMapper.readValue(result, DevPilotFailedResponse.class)
                .getError()
                .getMessage());
        }
    }

}
