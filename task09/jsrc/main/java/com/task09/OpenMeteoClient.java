package com.task09;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class OpenMeteoClient {

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenMeteoClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public String getWeather(double latitude, double longitude) throws IOException, InterruptedException {
        String url = String.format("%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m",
                BASE_URL, latitude, longitude);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return parseWeatherResponse(response.body());
        } else {
            return "Failed to fetch weather data. HTTP Status: " + response.statusCode();
        }
    }

    private String parseWeatherResponse(String responseBody) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        JsonNode current = jsonNode.path("current");

        double temperature = current.path("temperature_2m").asDouble();
        double windSpeed = current.path("wind_speed_10m").asDouble();

        return String.format("Current Temperature: %.2f°C, Wind Speed: %.2f m/s", temperature, windSpeed);
    }
}
