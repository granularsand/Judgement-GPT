package com.granularsand.jgpt;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;

public class OpenAI {

    public static String makeOpenAIRequest(String url, String apiKey, String requestBody) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);

        // Set the request headers
        httpPost.setHeader("Authorization", "Bearer " + apiKey);
        httpPost.setHeader("Content-Type", "application/json");

        // Set the request body
        httpPost.setEntity(new StringEntity(requestBody));

        // Execute the request
        HttpResponse response = httpClient.execute(httpPost);

        // Read the response
        HttpEntity responseEntity = response.getEntity();
        String responseString = EntityUtils.toString(responseEntity);

        // Close the response
        EntityUtils.consume(responseEntity);

        return responseString;
    }

    public static class Message {
        String role;
        String content;
    }
    public static class Choice {
        int index;
        Message message;
        String finish_reason;
    }
    public static class Usage {
        int prompt_tokens;
        int completion_tokens;
        int total_tokens;
    }
    public static class Completion {
        String id;
        String object;
        int created;
        String model;
        Choice[] choices;
        Usage usage;
    }
}
