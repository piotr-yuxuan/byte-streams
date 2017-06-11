(ns byte-streams.char-sequence
  (:refer-clojure :exclude [flush])
  (:import
    [java.util.concurrent.locks
     ReentrantLock]
    [java.io
     ByteArrayOutputStream]
    [java.nio
     ByteBuffer
     CharBuffer]
    [java.nio.charset
     Charset
     CharsetDecoder
     CoderResult
     CodingErrorAction]))

(set! *unchecked-math* true)

(defn coding-error-action [action]
  (case
    :report  CodingErrorAction/REPORT
    :ignore  CodingErrorAction/IGNORE
    :replace CodingErrorAction/REPLACE))

(defn parse-result [^CoderResult result]
  (cond
    (.isUnderflow result) :underflow
    (.isOverflow result) :overflow
    :else (throw (IllegalArgumentException. "Malformed byte-stream input to CharsetDecoder"))))

(defn decode
  [^CharsetDecoder decoder ^ByteBuffer in ^CharBuffer out]
  (parse-result (.decode decoder in out false)))

(defn flush
  ([decoder out] (flush decoder (ByteBuffer/allocate 0) out))
  ([^CharsetDecoder decoder ^ByteBuffer in ^CharBuffer out]
   (and
    (parse-result (.decode decoder in out true))
    (parse-result (.flush decoder out)))))

(defn has-remaining-bytes? [^ByteBuffer byte-buffer]
  {:pre [(some? byte-buffer)]}
  (.hasRemaining byte-buffer))

(defn merge-byte-buffers [^ByteBuffer l ^ByteBuffer r]
  {:pre [(some? l) (some? r)]}
  (-> (ByteBuffer/allocate (+ (.remaining l) (.remaining r)))
      (.put l)
      (.put r)
      .flip))

(defn lazy-char-buffer-sequence
  [^CharsetDecoder decoder
   chunk-size
   ^ByteBuffer extra-bytes
   close-fn
   byte-source]
  (lazy-seq
    (let [num-bytes (+ (long
                         (if extra-bytes
                           (.remaining extra-bytes)
                           0))
                      (long chunk-size))
          len (long
                (Math/ceil
                  (/ num-bytes
                    (.averageCharsPerByte decoder))))
          out (CharBuffer/allocate len)]

      (if (and extra-bytes (= :overflow (decode decoder extra-bytes out)))

        ;; we didn't even exhaust the overflow bytes, try again
        (cons
          out
          (lazy-char-buffer-sequence decoder chunk-size extra-bytes close-fn byte-source))

        (if-let [in (byte-source chunk-size)]
          (let [expanded-in (if (some-> extra-bytes has-remaining-bytes?)
                              ;; in case of underflow we need to pass new buffer
                              ;; containing remaining bytes from the initial input
                              ;; along with some new bytes to the CharsetDecoder
                              (merge-byte-buffers extra-bytes in)
                              in)
                result (decode decoder expanded-in out)]
            (cons
              (.flip out)
              (lazy-char-buffer-sequence
                decoder
                chunk-size
                (when (has-remaining-bytes? expanded-in)
                  expanded-in)
                close-fn
                byte-source)))
          (do
            (if (some? extra-bytes)
              (flush decoder extra-bytes out)
              (flush decoder out))
            (when close-fn (close-fn))
            (.flip out)))))))

(defn decode-byte-source
  [byte-source
   close-fn
   {:keys [chunk-size encoding on-encoding-error]
    :or {chunk-size 4096
         on-encoding-error :replace
         encoding "UTF-8"}}]
  (let [action (coding-error-action on-encoding-error)
        decoder (doto (.newDecoder (Charset/forName encoding))
                  (.onMalformedInput action)
                  (.onUnmappableCharacter action))
        s (lazy-char-buffer-sequence decoder chunk-size nil close-fn byte-source)]
    (reify
      java.io.Closeable
      (close [_] (when close-fn (close-fn)))

      CharSequence
      (charAt [_ idx]
        (loop [remaining idx, s s]
          (if (empty? s)
            (throw (IndexOutOfBoundsException. (str idx)))
            (let [^CharBuffer buf (first s)]
              (if (< (.remaining buf) remaining)
                (.charAt buf remaining)
                (recur (- remaining (.remaining buf)) (rest s)))))))
      (length [_]
        (reduce + (map #(.remaining ^CharBuffer %) s)))
      #_(subSequence [_ start end]
        )
      (toString [_]
        (let [buf (StringBuffer.)]
          (doseq [b s]
            (.append buf b))
          (.toString buf))))))
