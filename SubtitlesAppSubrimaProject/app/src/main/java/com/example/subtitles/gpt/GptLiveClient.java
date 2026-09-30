package com.example.subtitles.gpt;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GptLiveClient {

    private static final String TRANSCRIPTION_URL =
            "https://api.openai.com/v1/audio/transcriptions";

    private static final String RESPONSES_URL =
            "https://api.openai.com/v1/responses";

    private final OkHttpClient client;
    private final String apiKey;

    public GptLiveClient(String apiKey) {

        this.apiKey = apiKey;

        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Распознавание речи из WAV-файла.
     */
    public String transcribe(File audioFile) throws Exception {

        if (audioFile == null || !audioFile.exists()) {
            throw new IOException("Audio file not found");
        }

        RequestBody audioBody =
                RequestBody.create(
                        audioFile,
                        MediaType.parse("audio/wav")
                );

        MultipartBody requestBody =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                                "file",
                                audioFile.getName(),
                                audioBody
                        )
                        .addFormDataPart(
                                "model",
                                "gpt-4o-mini-transcribe"
                        )
                        .addFormDataPart(
                                "response_format",
                                "json"
                        )
                        .build();

        Request request =
                new Request.Builder()
                        .url(TRANSCRIPTION_URL)
                        .addHeader(
                                "Authorization",
                                "Bearer " + apiKey
                        )
                        .post(requestBody)
                        .build();

        try (Response response =
                     client.newCall(request).execute()) {

            String body =
                    response.body() != null
                            ? response.body().string()
                            : "";

            if (!response.isSuccessful()) {

                throw new IOException(
                        "Transcription HTTP " +
                                response.code() +
                                ": " +
                                body
                );
            }

            JSONObject json =
                    new JSONObject(body);

            return json.optString(
                    "text",
                    ""
            );
        }
    }

    /**
     * Перевод распознанного текста.
     */
    public String translate(
            String text,
            String sourceLanguage,
            String targetLanguage) throws Exception {

        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String prompt =
                "Translate the following spoken text from " +
                sourceLanguage +
                " to " +
                targetLanguage +
                ". " +
                "Return only the translation. " +
                "Preserve names, numbers and meaning. " +
                "Do not add explanations.\n\n" +
                text;

        JSONObject input =
                new JSONObject();

        input.put(
                "model",
                "gpt-5.6-luna"
        );

        input.put(
                "input",
                prompt
        );

        RequestBody body =
                RequestBody.create(
                        input.toString(),
                        MediaType.parse(
                                "application/json; charset=utf-8"
                        )
                );

        Request request =
                new Request.Builder()
                        .url(RESPONSES_URL)
                        .addHeader(
                                "Authorization",
                                "Bearer " + apiKey
                        )
                        .addHeader(
                                "Content-Type",
                                "application/json"
                        )
                        .post(body)
                        .build();

        try (Response response =
                     client.newCall(request).execute()) {

            String responseBody =
                    response.body() != null
                            ? response.body().string()
                            : "";

            if (!response.isSuccessful()) {

                throw new IOException(
                        "Translation HTTP " +
                                response.code() +
                                ": " +
                                responseBody
                );
            }

            return extractResponseText(
                    responseBody
            );
        }
    }

    private String extractResponseText(
            String responseBody) throws Exception {

        JSONObject json =
                new JSONObject(responseBody);

        String direct =
                json.optString(
                        "output_text",
                        ""
                );

        if (!direct.isEmpty()) {
            return direct.trim();
        }

        if (!json.has("output")) {
            return "";
        }

        org.json.JSONArray output =
                json.getJSONArray("output");

        StringBuilder result =
                new StringBuilder();

        for (int i = 0;
             i < output.length();
             i++) {

            JSONObject item =
                    output.optJSONObject(i);

            if (item == null) {
                continue;
            }

            org.json.JSONArray content =
                    item.optJSONArray("content");

            if (content == null) {
                continue;
            }

            for (int j = 0;
                 j < content.length();
                 j++) {

                JSONObject part =
                        content.optJSONObject(j);

                if (part == null) {
                    continue;
                }

                String text =
                        part.optString(
                                "text",
                                ""
                        );

                if (!text.isEmpty()) {
                    result.append(text);
                }
            }
        }

        return result.toString().trim();
    }
}
