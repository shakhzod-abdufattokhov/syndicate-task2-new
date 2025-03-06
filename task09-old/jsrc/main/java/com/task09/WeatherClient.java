package com.task09;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class WeatherClient {

    private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast?latitude=50.4375&longitude=30.5&current_weather=true&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public JsonNode fetchWeatherData() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WEATHER_API_URL))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("error", "Failed to fetch weather data: " + e.getMessage());
        }
    }

}
