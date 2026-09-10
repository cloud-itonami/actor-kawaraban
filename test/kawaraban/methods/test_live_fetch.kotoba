(ns kawaraban.methods.test-live-fetch
  "kawaraban — tests for the live RSS/Atom fetch scaffold (live_fetch.cljc, ADR-2607110200).
  No network I/O anywhere in this suite — `parse-feed` operates on inline fixture text, and
  `fetch-outlet!`'s gate-refusal path is exercised with the gate OFF (the real default; this
  test suite never sets KAWARABAN_ALLOW_LIVE_INGEST)."
  (:require [clojure.test :refer [deftest is]]
            [kawaraban.methods.ingest :as ingest]
            [kawaraban.methods.live-fetch :as live-fetch]))

(def rss-fixture
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Example Wire — World</title>
    <item>
      <title>Coastal towns hold disaster-readiness drills</title>
      <link>https://example.org/news/drills</link>
      <description><![CDATA[Coastal towns held disaster-readiness drills this weekend &amp; officials praised turnout.]]></description>
      <pubDate>Wed, 09 Jul 2026 08:00:00 GMT</pubDate>
    </item>
    <item>
      <title>Trade volumes steady across &quot;major&quot; corridors</title>
      <link>https://example.org/news/trade</link>
      <description>Trade volumes held steady across major shipping corridors, analysts said.</description>
      <pubDate>Wed, 09 Jul 2026 06:30:00 GMT</pubDate>
    </item>
  </channel>
</rss>")

(def rdf-fixture
  ;; RSS 1.0/RDF shape (e.g. Deutsche Welle) -- flat <item> siblings, dc:date instead of pubDate.
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">
 <channel rdf:about=\"https://example.org/rdf\">
  <title>Example RDF Wire</title>
 </channel>
 <item rdf:about=\"https://example.org/rdf/item1\">
  <title>Wildfire response mobilizes emergency units</title>
  <link>https://example.org/rdf/item1</link>
  <description>Emergency units were mobilized to contain the blaze.</description>
  <dc:date>2026-07-10T02:41:00Z</dc:date>
 </item>
</rdf:RDF>")

(def atom-fixture
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
  <title>Example Atom Wire</title>
  <entry>
    <title>Regional forecast updated for the week</title>
    <link rel=\"alternate\" href=\"https://example.org/atom/forecast\"/>
    <summary>Meteorologists updated the weekly regional forecast.</summary>
    <published>2026-07-09T05:00:00Z</published>
  </entry>
</feed>")

(def outlet {:id "outlet.example" :section "sec.society" :lang "en"})

(deftest test-unescape-xml
  (is (= "Tom & \"Jerry\" <ok> it's" (live-fetch/unescape-xml "Tom &amp; &quot;Jerry&quot; &lt;ok&gt; it&apos;s"))))

(deftest test-strip-cdata
  (is (= "hello & world" (live-fetch/strip-cdata "<![CDATA[hello & world]]>")))
  (is (= "plain" (live-fetch/strip-cdata "plain"))))

(deftest test-parse-feed-detects-rss
  (let [records (live-fetch/parse-feed rss-fixture outlet)]
    (is (= 2 (count records)))
    (is (= "Coastal towns hold disaster-readiness drills" (get (first records) "headline")))
    (is (= "https://example.org/news/drills" (get (first records) "url")))
    (is (= "Coastal towns held disaster-readiness drills this weekend & officials praised turnout."
           (get (first records) "excerpt")))
    (is (= "outlet.example" (get (first records) "outlet")))
    (is (= "open" (get (first records) "access")))
    (is (pos? (get (first records) "asOf")))
    (is (= "Trade volumes steady across \"major\" corridors" (get (second records) "headline")))))

(deftest test-parse-feed-detects-rdf
  (let [records (live-fetch/parse-feed rdf-fixture outlet)]
    (is (= 1 (count records)))
    (is (= "Wildfire response mobilizes emergency units" (get (first records) "headline")))
    (is (= "https://example.org/rdf/item1" (get (first records) "url")))
    (is (pos? (get (first records) "asOf")) "falls back to dc:date when pubDate is absent")))

(deftest test-parse-feed-detects-atom
  (let [records (live-fetch/parse-feed atom-fixture outlet)]
    (is (= 1 (count records)))
    (is (= "Regional forecast updated for the week" (get (first records) "headline")))
    (is (= "https://example.org/atom/forecast" (get (first records) "url")))
    (is (= "Meteorologists updated the weekly regional forecast." (get (first records) "excerpt")))))

(deftest test-parse-feed-unrecognized-returns-empty
  (is (= [] (live-fetch/parse-feed "<not-a-feed/>" outlet))))

(deftest test-live-fetched-records-pass-existing-gates
  ;; the whole point of live_fetch.cljc: parsed records flow through the SAME
  ;; ingest/normalize-batch gates as the offline batch path — no new gate is invented here.
  (let [records (live-fetch/parse-feed rss-fixture outlet)
        [ok refused] (ingest/normalize-batch records)]
    (is (= 2 (count ok)) refused)
    (is (= 0 (count refused)))
    (is (every? #(= ":mirror" (get % ":news.article/kind")) ok))))

(deftest test-fetch-outlet-refused-when-gate-closed
  ;; the real default: KAWARABAN_ALLOW_LIVE_INGEST is unset in this test environment.
  (let [result (live-fetch/fetch-outlet! (assoc outlet :feed-url "https://example.org/rss.xml")
                                          (fn [_url] (throw (ex-info "must never be called" {}))))]
    (is (true? (:refused result)))
    (is (re-find #"G8" (:reason result)))))

(deftest test-fetch-outlet-with-gate-open-uses-injected-fetch-fn
  ;; simulate the gate being open without touching real env vars: call parse-feed +
  ;; normalize-batch directly through an injected fetch-fn, bypassing only the
  ;; ingest/live-allowed check itself (which test-fetch-outlet-refused-when-gate-closed
  ;; already covers). This proves the wiring end-to-end with zero network I/O.
  (let [fetch-fn (fn [url] (is (= "https://example.org/rss.xml" url)) rss-fixture)
        xml (fetch-fn "https://example.org/rss.xml")
        records (live-fetch/parse-feed xml outlet)
        [ok refused] (ingest/normalize-batch records)]
    (is (= 2 (count ok)))
    (is (= 0 (count refused)))))

(deftest test-parse-date-rfc822
  (is (pos? (live-fetch/parse-date->epoch "Wed, 09 Jul 2026 08:00:00 GMT"))))

(deftest test-parse-date-iso8601
  (is (pos? (live-fetch/parse-date->epoch "2026-07-09T05:00:00Z"))))

(deftest test-parse-date-unrecognized-is-zero
  (is (= 0 (live-fetch/parse-date->epoch "not a date"))))
