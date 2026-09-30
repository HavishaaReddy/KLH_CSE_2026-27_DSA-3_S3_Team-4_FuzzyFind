FUZZYFIND - WORKING JAVA WEBSITE

IMPORTANT:
This version does NOT depend on JavaScript for the Search button.

The Search button is a normal HTML form:
<form action="/search" method="GET">

Clicking Search sends the query directly to the Java server.
Java calculates the actual Levenshtein Distance and returns a new
results page.

RUN:

1. Stop any old server with Ctrl+C.
2. Open this folder in VS Code.
3. Terminal:
   javac FuzzyFindServer.java
4. Then:
   java FuzzyFindServer
5. Open:
   http://localhost:8080

FIRST RUN:
The program downloads words_alpha.txt once.
Internet is required for the first download.

TEST:
Type:
teh
and click Search.

Then try:
computr
banani
javscript
restarant
or any other query.

NOTE:
The algorithm can only suggest words that exist in the dictionary.
The user's input itself can be any word/query.
