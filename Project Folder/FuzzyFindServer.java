import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class FuzzyFindServer {

    static final int PORT = 8080;
    static final String DICTIONARY_FILE = "words_alpha.txt";
    static final String DICTIONARY_URL =
            "https://raw.githubusercontent.com/dwyl/english-words/master/words_alpha.txt";

    static final List<String> dictionary = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        loadDictionary();

        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0);

        server.createContext("/", FuzzyFindServer::home);
        server.createContext("/search", FuzzyFindServer::search);

        server.start();

        System.out.println();
        System.out.println("========================================");
        System.out.println("          FUZZYFIND JAVA SERVER");
        System.out.println("========================================");
        System.out.println("Words loaded : " + dictionary.size());
        System.out.println("Open         : http://localhost:8080");
        System.out.println("========================================");
    }

    // Load the dictionary from a text file.
    static void loadDictionary() throws IOException {

        Path file = Paths.get(DICTIONARY_FILE);

        if (!Files.exists(file)) {

            System.out.println("words_alpha.txt not found.");
            System.out.println("Downloading dictionary...");

            URL url = URI.create(DICTIONARY_URL).toURL();

            try (InputStream in = url.openStream();
                 OutputStream out = Files.newOutputStream(file)) {

                byte[] buffer = new byte[8192];
                int n;

                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }

            System.out.println("Dictionary downloaded.");
        }

        try (BufferedReader br = Files.newBufferedReader(
                file, StandardCharsets.UTF_8)) {

            String word;

            while ((word = br.readLine()) != null) {

                word = word.trim().toLowerCase(Locale.ROOT);

                if (word.matches("[a-z]+")) {
                    dictionary.add(word);
                }
            }
        }

        // These common words are guaranteed to exist.
        String[] common = {
                "the", "this", "that", "then", "there", "their",
                "cat", "car", "can", "cake", "care", "case",
                "banana", "apple", "computer", "computing",
                "algorithm", "database", "javascript", "java",
                "fuzzy", "search", "website", "student", "project"
        };

        for (String word : common) {
            if (!dictionary.contains(word)) {
                dictionary.add(word);
            }
        }
    }

    // Standard Levenshtein Distance.
    //
    // Allowed operations:
    // insertion = 1
    // deletion  = 1
    // replacement = 1
    //
    // Example:
    // cat -> cut = 1
    // cat -> cart = 1
    // cat -> dog = 3
    static int levenshtein(String a, String b) {

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {

            current[0] = i;

            for (int j = 1; j <= b.length(); j++) {

                int insertion =
                        current[j - 1] + 1;

                int deletion =
                        previous[j] + 1;

                int replacement =
                        previous[j - 1] +
                        (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);

                current[j] = Math.min(
                        insertion,
                        Math.min(deletion, replacement)
                );
            }

            int[] temp = previous;
            previous = current;
            current = temp;
        }

        return previous[b.length()];
    }

    static List<Result> findMatches(String query) {

        query = query.toLowerCase(Locale.ROOT).trim();

        List<Result> results = new ArrayList<>();

        if (query.isEmpty()) {
            return results;
        }

        /*
         * Normal search threshold.
         * This prevents unnecessary calculations for words
         * that are obviously very far from the query.
         */
        int threshold;

        if (query.length() <= 3) {
            threshold = 1;
        } else if (query.length() <= 6) {
            threshold = 2;
        } else if (query.length() <= 10) {
            threshold = 3;
        } else {
            threshold = 4;
        }

        for (String word : dictionary) {

            if (Math.abs(query.length() - word.length()) > threshold) {
                continue;
            }

            int distance = levenshtein(query, word);

            if (distance <= threshold) {

                int maxLength =
                        Math.max(query.length(), word.length());

                int similarity =
                        maxLength == 0
                        ? 100
                        : (int)Math.round(
                            (1.0 -
                            (double)distance / maxLength) * 100
                          );

                results.add(
                        new Result(word, distance, similarity)
                );
            }
        }

        /*
         * Ranking:
         * 1. smallest Levenshtein distance
         * 2. highest similarity
         * 3. alphabetical order
         */
        results.sort(
                Comparator
                .comparingInt((Result r) -> r.distance)
                .thenComparing(
                        Comparator
                        .comparingInt(
                                (Result r) -> r.similarity)
                        .reversed()
                )
                .thenComparing(r -> r.word)
        );

        /*
         * If the normal threshold finds nothing,
         * find the actual closest words.
         */
        if (results.isEmpty()) {

            for (String word : dictionary) {

                int distance =
                        levenshtein(query, word);

                int maxLength =
                        Math.max(query.length(), word.length());

                int similarity =
                        maxLength == 0
                        ? 100
                        : Math.max(
                            0,
                            (int)Math.round(
                                (1.0 -
                                (double)distance / maxLength) * 100
                            )
                          );

                results.add(
                        new Result(word, distance, similarity)
                );
            }

            results.sort(
                    Comparator
                    .comparingInt((Result r) -> r.distance)
                    .thenComparing(
                            Comparator
                            .comparingInt(
                                    (Result r) -> r.similarity)
                            .reversed()
                    )
                    .thenComparing(r -> r.word)
            );
        }

        if (results.size() > 10) {
            return new ArrayList<>(
                    results.subList(0, 10)
            );
        }

        return results;
    }

    // Home page.
    static void home(HttpExchange exchange) throws IOException {
        send(exchange, page("", new ArrayList<>()), "text/html");
    }

    // IMPORTANT:
    // The Search button uses a normal HTML FORM.
    // No JavaScript is needed for searching.
    static void search(HttpExchange exchange) throws IOException {

        String query =
                getQueryParameter(
                        exchange.getRequestURI(), "q");

        List<Result> results =
                findMatches(query);

        send(
                exchange,
                page(query, results),
                "text/html"
        );
    }

    static String getQueryParameter(
            URI uri, String name) {

        String raw = uri.getRawQuery();

        if (raw == null) {
            return "";
        }

        for (String part : raw.split("&")) {

            String[] pair =
                    part.split("=", 2);

            if (pair.length == 2 &&
                    pair[0].equals(name)) {

                return URLDecoder.decode(
                        pair[1],
                        StandardCharsets.UTF_8
                );
            }
        }

        return "";
    }

    static String page(
            String query,
            List<Result> results) {

        StringBuilder resultHTML =
                new StringBuilder();

        if (!query.isEmpty()) {

            if (results.isEmpty()) {

                resultHTML.append(
                        "<div class='no-result'>" +
                        "No result found." +
                        "</div>"
                );

            } else {

                Result best = results.get(0);

                resultHTML.append(
                        "<div class='closest'>" +
                        "<div class='small-title'>" +
                        "CLOSEST MATCH" +
                        "</div>" +

                        "<div class='closest-row'>" +

                        "<div>" +
                        "<div class='did-you-mean'>" +
                        "Closest match: " +
                        "<strong>" +
                        escapeHTML(best.word) +
                        "</strong>" +
                        "</div>" +

                        "<div class='score'>" +
                        "Levenshtein Distance: " +
                        best.distance +
                        " &nbsp; | &nbsp; " +
                        "Similarity: " +
                        best.similarity +
                        "%" +
                        "</div>" +

                        "</div>" +

                        "<a class='use-button' href='/?q=" +
                        urlEncode(best.word) +
                        "'>" +
                        "Use this" +
                        "</a>" +

                        "</div>" +
                        "</div>"
                );

                resultHTML.append(
                        "<h3>Ranked Suggestions</h3>"
                );

                for (int i = 0;
                     i < results.size();
                     i++) {

                    Result r = results.get(i);

                    resultHTML.append(
                            "<div class='result'>" +

                            "<span class='number'>#" +
                            (i + 1) +
                            "</span>" +

                            "<span class='word'>" +
                            escapeHTML(r.word) +
                            "</span>" +

                            "<span class='distance'>" +
                            "Distance: " +
                            r.distance +
                            "</span>" +

                            "<span class='similarity'>" +
                            r.similarity +
                            "%" +
                            "</span>" +

                            "</div>"
                    );
                }
            }
        }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport"
content="width=device-width,initial-scale=1.0">

<title>FuzzyFind</title>

<style>

* {
    box-sizing: border-box;
}

body {
    margin: 0;
    font-family: Arial, sans-serif;
    background: #f5f8f8;
    color: #152630;
}

nav {
    height: 72px;
    background: white;
    border-bottom: 1px solid #e1e6e7;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8%;
}

.logo {
    font-size: 23px;
    font-weight: bold;
}

.logo span {
    background: #14252e;
    color: white;
    padding: 7px 11px;
    border-radius: 8px;
    margin-right: 8px;
}

nav a {
    color: #617078;
    text-decoration: none;
    margin-left: 25px;
}

.hero {
    padding: 80px 9%;
    background: white;
}

.tag {
    color: #0d8d80;
    letter-spacing: 2px;
    font-size: 11px;
    font-weight: bold;
}

h1 {
    font-size: 60px;
    line-height: 1.05;
    margin: 18px 0;
}

h1 span {
    color: #0d8d80;
}

.hero p {
    color: #66757e;
    max-width: 720px;
    font-size: 18px;
    line-height: 1.7;
}

.search-section {
    padding: 75px 9%;
    min-height: 600px;
}

.search-section h2 {
    font-size: 44px;
    margin: 12px 0;
}

.subtitle {
    color: #697780;
}

.search-form {
    max-width: 950px;
    margin: 30px auto 10px;
    display: flex;
    background: white;
    border: 1px solid #d9e0e2;
    border-radius: 15px;
    padding: 7px;
}

.search-form input {
    flex: 1;
    border: 0;
    outline: 0;
    padding: 18px;
    font-size: 18px;
}

.search-form button {
    border: 0;
    background: #0d8d80;
    color: white;
    border-radius: 10px;
    padding: 0 35px;
    font-size: 16px;
    font-weight: bold;
    cursor: pointer;
}

.search-form button:hover {
    background: #14252e;
}

.content {
    max-width: 950px;
    margin: 30px auto;
}

.closest {
    background: #e7f7f4;
    border: 1px solid #bce5de;
    border-radius: 14px;
    padding: 22px;
    margin-bottom: 25px;
}

.small-title {
    color: #0d8d80;
    font-size: 10px;
    letter-spacing: 2px;
    font-weight: bold;
}

.closest-row {
    margin-top: 10px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
}

.did-you-mean {
    font-size: 23px;
}

.did-you-mean strong {
    color: #0d8d80;
}

.score {
    margin-top: 6px;
    color: #627179;
    font-size: 13px;
}

.use-button {
    background: #14252e;
    color: white;
    text-decoration: none;
    padding: 12px 18px;
    border-radius: 8px;
    font-weight: bold;
}

.use-button:hover {
    background: #0d8d80;
}

.result {
    background: white;
    border: 1px solid #dfe5e7;
    border-radius: 10px;
    margin: 8px 0;
    padding: 18px;
    display: grid;
    grid-template-columns: 55px 1fr 160px 70px;
    align-items: center;
}

.number {
    color: #9aa5aa;
}

.word {
    font-weight: bold;
    font-size: 16px;
}

.distance {
    color: #65737a;
    font-size: 13px;
}

.similarity {
    color: #0d8d80;
    font-weight: bold;
    text-align: right;
}

.no-result {
    background: white;
    border: 1px solid #ddd;
    padding: 25px;
    border-radius: 12px;
}

footer {
    padding: 30px 9%;
    background: #12232c;
    color: white;
}

@media(max-width:700px) {

    nav a {
        display: none;
    }

    h1 {
        font-size: 45px;
    }

    .search-form {
        flex-direction: column;
    }

    .search-form button {
        height: 50px;
    }

    .closest-row {
        flex-direction: column;
        align-items: flex-start;
    }

    .result {
        grid-template-columns: 35px 1fr;
        gap: 7px;
    }

    .distance,
    .similarity {
        text-align: left;
    }
}

</style>
</head>

<body>

<nav>

<div class="logo">
<span>F</span>FuzzyFind
</div>

<div>
<a href="/">Home</a>
<a href="#search">Search</a>
<a href="#algorithm">Algorithm</a>
</div>

</nav>

<section class="hero">

<div class="tag">
JAVA • DATA STRUCTURES & ALGORITHMS
</div>

<h1>
Search smarter.<br>
<span>Even with typos.</span>
</h1>

<p>
FuzzyFind compares your search query with a large dictionary
using the Levenshtein Distance algorithm and ranks the closest
matches by the number of edits required.
</p>

</section>

<section class="search-section" id="search">

<div class="tag">
LIVE JAVA DEMO
</div>

<h2>
Find the closest match
</h2>

<p class="subtitle">
Enter any word or search query.
</p>

<form class="search-form"
      action="/search"
      method="GET">

<input
    name="q"
    value="__QUERY__"
    placeholder="Try: computr, teh, banani, javscript..."
    autocomplete="off">

<button type="submit">
Search
</button>

</form>

<div class="content">

__RESULTS__

</div>

</section>

<section id="algorithm"
style="padding:75px 9%;background:white;">

<div class="tag">
HOW IT WORKS
</div>

<h2>
Levenshtein Distance
</h2>

<p style="color:#687780;max-width:800px;line-height:1.7;">

The algorithm calculates the minimum number of
single-character insertions, deletions and replacements
needed to transform the entered word into each dictionary
word. The results are then sorted by the smallest distance.

</p>

</section>

<footer>
<strong>FuzzyFind</strong>
<br><br>
Java + Levenshtein Distance • DSA-3 Project
</footer>

</body>
</html>
"""
        .replace("__QUERY__", escapeHTML(query))
        .replace("__RESULTS__", resultHTML.toString());
    }

    static String escapeHTML(String s) {

        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String urlEncode(String s) {

        return URLEncoder.encode(
                s,
                StandardCharsets.UTF_8
        );
    }

    static void send(
            HttpExchange exchange,
            String text,
            String type)
            throws IOException {

        byte[] bytes =
                text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set(
                    "Content-Type",
                    type + "; charset=UTF-8"
                );

        exchange.sendResponseHeaders(
                200,
                bytes.length
        );

        try (OutputStream out =
                     exchange.getResponseBody()) {

            out.write(bytes);
        }
    }

    static class Result {

        String word;
        int distance;
        int similarity;

        Result(
                String word,
                int distance,
                int similarity) {

            this.word = word;
            this.distance = distance;
            this.similarity = similarity;
        }
    }
}
