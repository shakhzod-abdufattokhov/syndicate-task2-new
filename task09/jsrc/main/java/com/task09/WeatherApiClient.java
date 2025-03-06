package com.task09;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class WeatherApiClient {
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    public String fetchWeatherData(double latitude, double longitude) {
        String urlString = BASE_URL + "?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,wind_speed_10m";

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();

            // Check if response is 200 OK
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return "Error: Unable to fetch data";
            }

            // Read response
            Scanner scanner = new Scanner(url.openStream());
            StringBuilder response = new StringBuilder();
            while (scanner.hasNext()) {
                response.append(scanner.nextLine());
            }
            scanner.close();

            // Parse JSON response
            JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
            return jsonObject.toString();

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}
