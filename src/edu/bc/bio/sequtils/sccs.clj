;;--------------------------------------------------------------------------;;
;;                                                                          ;;
;;                      S E Q U T I L S . S C C S                           ;;
;;                                                                          ;;
;;                                                                          ;;
;; Copyright (c) 2011-2014 Trustees of Boston College                       ;;
;;                                                                          ;;
;; Permission is hereby granted, free of charge, to any person obtaining    ;;
;; a copy of this software and associated documentation files (the          ;;
;; "Software"), to deal in the Software without restriction, including      ;;
;; without limitation the rights to use, copy, modify, merge, publish,      ;;
;; distribute, sublicense, and/or sell copies of the Software, and to       ;;
;; permit persons to whom the Software is furnished to do so, subject to    ;;
;; the following conditions:                                                ;;
;;                                                                          ;;
;; The above copyright notice and this permission notice shall be           ;;
;; included in all copies or substantial portions of the Software.          ;;
;;                                                                          ;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,          ;;
;; EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF       ;;
;; MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND                    ;;
;; NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE   ;;
;; LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION   ;;
;; OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION    ;;
;; WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.          ;;
;;                                                                          ;;
;; Author: Jon Anthony                                                      ;;
;;                                                                          ;;
;;--------------------------------------------------------------------------;;
;;

(ns edu.bc.bio.sequtils.sccs

  "Sequence Conservation Context and Size similarity matching and
   filtering.  Used in both (semi)annotated situations (refseq and
   kin) or no annotation situations (typically metagenomic /
   microbiome).  SCCS is primarily an automatic annotation and
   alignment free computational system for similarity matching of
   sequence regions.  These regions may be ncRNAs (e.g., our (Meyer
   Lab) current focus of ribosomal regulatory structures in
   prokaryotes), coding structures (single genes, operons).  Other
   regions may be possible or sensible.

   We used a suite of unaligned techniques based information theory
   and particularly optimal 'word size', entropy, min and max entropy
   distribution extensions, information capacity differential, and
   relative entropy (typically JSD variant for normalized and
   symmetric operation), commulative relative entropy (CRE),
   commulative distribution functions (CDFs - for cutpoint
   selection).

   Additionally, we use a variant of Chameleon clustering (RECORD)
   with S_Dbw cluster validity index to split multiple sequence
   contexts for further processing.

   In all cases we make heavy use of parallel folding based in
   reducers for large scale parallel processing."

  (:require [clojure.contrib.string :as str]
            [clojure.set :as set]
            [clojure.contrib.io :as io]
            [clojure-csv.core :as csv]
            [incanter.core]
            [incanter.charts]
            [edu.bc.fs :as fs]
            [edu.bc.utils.graphs :as gr]
            [edu.bc.utils.clustering :as clu]
            [edu.bc.bio.sequtils.tools :as tools])
  (:use clojure.contrib.math
        edu.bc.utils
        edu.bc.utils.probs-stats
        edu.bc.bio.seq-utils
        edu.bc.bio.sequtils.files
        [clojure.pprint
         :only [cl-format]]
        ))


;;;--------- REFACTOR - The following needs to go into info-theory or ??? ;;;

(defn expected-qdict [q-1 q-2 & {:keys [alpha] :or {alpha (alphabet :rna)}}]
  (reduce (fn[m lmer]
            (let [l (dec (count lmer))
                  x (subs lmer 1)
                  y (subs lmer 0 l)
                  z (subs lmer 1 l)]
              (if (and (q-1 x) (q-1 y) (q-2 z))
                (assoc m lmer (/ (* (q-1 x) (q-1 y)) (q-2 z)))
                m)))
          {} (for [x (keys q-1) a alpha] (str x a))))


(defn freq-xdict-dict
  [q sq]
  (let [ext-sq (str sq "X")
        residue-key (subs ext-sq (- (count sq) (- q 1)))
        q-xfreq-dict (freqn q ext-sq)
        q-freq-dict (dissoc q-xfreq-dict residue-key)]
    [q-xfreq-dict q-freq-dict]))

(defn q-1-dict
  ([q-xdict]
     (reduce (fn[m [k v]]
               (let [l (count k)
                     pre (subs k 0 (dec l))]
                 (assoc m pre (+ (get m pre 0) v))))
             {} q-xdict))
  ([q sq]
     (probs (dec q) sq)))

(defn q1-xdict-dict
  [q sq & {:keys [ffn] :or {ffn probs}}]
  (let [[q-xfreq-dict q-freq-dict] (freq-xdict-dict q sq)
        q-xpdf-dict (probs q-xfreq-dict)
        q-pdf-dict (probs q-freq-dict)]
    {:xfreq q-xfreq-dict :xpdf q-xpdf-dict
     :freq q-freq-dict :pdf q-pdf-dict}))



(defn reconstruct-dict
  [l sq & {:keys [alpha] :or {alpha (alphabet :rna)}}]
  {:pre [(> l 2)]}
  (let [q (dec l)
        qmaps (q1-xdict-dict q sq)
        [q-xdict q-dict] (map qmaps [:xpdf :pdf])
        q-1dict (q-1-dict q-xdict)]
    (expected-qdict q-dict q-1dict :alpha alpha)))


(defn max-qdict-entropy
  [q & {:keys [alpha] :or {alpha (alphabet :rna)}}]
  (let [base (count alpha)]
    (* q (log2 base))))

(defn informativity
  ([q sq]
     (- (max-qdict-entropy q) (entropy (probs q sq))))
  ([q-dict]
     (let [q (count (first (keys q-dict)))]
       (- (max-qdict-entropy q) (entropy q-dict)))))


(defn limit-entropy
  [q|q-dict sq|q-1dict &
   {:keys [alpha NA] :or {alpha (alphabet :rna) NA -1.0}}]
  {:pre [(or (and (integer? q|q-dict)
                  (or (string? sq|q-1dict) (coll? sq|q-1dict)))
             (and (map? q|q-dict) (map? sq|q-1dict)))]}

  (if (map? q|q-dict)
    (let [q-dict q|q-dict
          q-1dict sq|q-1dict]
      (/ (- (entropy q-dict) (entropy q-1dict))
         (log2 (count alpha))))

    (let [q q|q-dict
          sq sq|q-1dict
          lgcnt (log2 (count alpha))]
      (if (= q 1)
        (/ (entropy (probs 1 sq)) lgcnt)

        (let [qmaps (q1-xdict-dict q sq)
              [q-xdict q-dict] (map qmaps [:xpdf :pdf])
              q-1dict (q-1-dict q-xdict)]
          (if (< (count q-dict) (count q-1dict))
            (if (fn? NA) (NA q-dict q-1dict) NA)
            (/ (- (entropy q-dict) (entropy q-1dict)) lgcnt)))))))


(defn limit-informativity
  ([q sq]
     )
  ([q-dict]
     ))


(defn CREl
  [l sq & {:keys [limit alpha]
           :or {limit 15 alpha (alphabet :rna)}}]
  {:pre [(> l 2)]}
  (sum (fn[k]
         (catch-all (DX||Y
                     (probs k sq)
                     (reconstruct-dict k sq :alpha alpha))))
       (range l (inc limit))))


(defn information-capacity
  [q sq & {:keys [cmpfn] :or {cmpfn jensen-shannon}}]
  (catch-all (cmpfn (probs q sq)
                    (reconstruct-dict q sq))))


(defn hybrid-dictionary
  "Compute the 'hybrid', aka centroid, dictionary or Feature Frequency
   Profile (FFP) for sqs.  SQS is either a collection of already
   computed FFPs (probability maps) of sequences, or a collection of
   sequences, or a string denoting a sequence file (sto, fasta, aln,
   ...) giving a collection of sequences.  In the latter cases, the
   sequences will have their FFPs computed based on word/feature
   length L (resolution size).  In all cases the FFPs are combined,
   using the minimum entropy principle, into a joint ('hybrid' /
   centroid) FFP.
  "
  [l sqs]
  {:pre [(or (string? sqs) (coll? sqs))]}

  (let [sqs (if (-> sqs first map?)
              sqs
              (degap-seqs (if (coll? sqs) sqs (read-seqs sqs))))
        cnt (count sqs)
        par (max (floor (/ cnt 10)) 2)
        dicts (if (-> sqs first map?) sqs (vfold #(probs l %) sqs))
        hybrid (apply merge-with +
                      (vfold (fn[subset] (apply merge-with + subset))
                             (partition-all (/ (count dicts) par) dicts)))]
    (reduce (fn[m [k v]] (assoc m k (double (/ v cnt))))
            {} hybrid)))


;;; (pxmap (fn[subset] (apply merge-with + subset))
;;;                              par (partition-all
;;;                                   (/ (count dicts) par)
;;;                                   dicts))
;;; (vfold (fn[subset] (apply merge-with + subset))
;;;                          (partition-all (/ (count dicts) par) dicts))

;;; 1774444 the number of keys!!

;;; (/ 186112 2)
;;; 57 253 960 bases
;;;
;;; 4 683 023 485 bases
;;; (/ 7412 2) => 3706
;;;
;;; (/ 4683023485 57253960.0)
;;; (/ 7512557802 57253960.0)


(defn ctx-seq
  [entry & {:keys [directed ldelta rdelta delta ddel db] :or {directed true}}]
  {:pre [(or delta (and (not directed) (or ldelta rdelta)))]}
  (let [ldelta (or delta ldelta 0)
        rdelta (or delta rdelta 0)]
    (if (not directed)
      (gen-name-seq entry :basedir db :ldelta ldelta :rdelta rdelta)
      (let [ddel (or ddel
                     (->> entry entry-parts second
                          (#(apply - %)) abs (#(/ % 4)) ceil))
            +? (= 1 (->> entry (pos \-) count))
            ldelta (if +? ddel delta)
            rdelta (if +? delta ddel)]
        (gen-name-seq entry :basedir db :ldelta ldelta :rdelta rdelta)))))

(defn get-adjusted-seqs
  ""
  [entries delta & {:keys [directed ldelta rdelta ddel] :or {directed true}}]
  (let [ddel (or ddel (if (= delta 0) 0 nil))
        db default-genome-fasta-dir] ; Must be a better way...
    (vfold #(ctx-seq % :directed directed
                     :db db
                     :delta delta :ddel ddel :ldelta ldelta :rdelta rdelta)
           entries)))

(defn cre-samples
  [seqs & {:keys [alpha xlate directed ldelta rdelta delta cnt limit]
           :or {alpha (alphabet :rna)
                directed true cnt 5 limit 14}}]
  {:pre [(or delta (and (not directed) ldelta rdelta))]}
  (let [entries (if (coll? seqs) seqs (doall (get-entries seqs)))
        cnt (min cnt (count entries))
        ;;ldelta (or delta ldelta)
        ;;rdelta (or delta rdelta)
        name-seq-pairs (get-adjusted-seqs entries delta
                                          :ldelta ldelta :rdelta rdelta)]
    (for [[nm sq] (random-subset name-seq-pairs cnt)
          :let [sq (if xlate (seqXlate sq :xmap xlate) sq)]]
      (vfold (fn[l] [l (CREl l sq :alpha alpha)
                     (count sq) nm])
             (range 3 (inc limit))))))


(defn nm-res-dist [nm-res]
  (let [frq (reduce (fn[m [nm re]]
                      (let [re (-> re (* 1000) (+ 0.1) round (/ 1000.0))]
                        (assoc m re (conj (get m re []) nm))))
                    {} nm-res)
        cnt (double (count nm-res))]
    (map (fn[[re nms]] [re (double (/ (count nms) cnt)) nms]) frq)))

(defn nm-res-cdf [nm-res]
  (let [re-dist (nm-res-dist nm-res)
        re-sorted (sort-by first re-dist)
        pre-sq (map (fn[[re p nms]] [p re]) re-sorted)]
    (fn[x]
      (reduce (fn[y [p re]]
                (if (<= re x) (+ y p) y))
              0.0 pre-sq))))




(defn render-chart
  ([location xs ys xlabel ylabel title & {:keys [series-label legend]}]
     (let [chart (if (or series-label legend)
                   (incanter.charts/scatter-plot
                    xs ys
                    :x-label xlabel :y-label ylabel :title title
                    :series-label series-label :legend legend)
                   (incanter.charts/scatter-plot
                    xs ys
                    :x-label xlabel :y-label ylabel :title title))]
       (render-chart chart location)))
  ([chart location]
     (if (= location :display)
       (incanter.core/view chart)
       (incanter.core/save chart location :width 600 :height 480))))

(defn write-plot-data-csv
  [file colhdr xs ys name]
  (spit file
        (csv/write-csv
         (cons colhdr
               (partition-all 3 (map str (interleave xs ys (repeat name))))))))


(defn plot-cres
  [cre-samples & {:keys [file]}]
  (doseq [cres cre-samples]
    (let [ks (map first cres)
          cnt (third (first cres))
          cres (map second cres)]
      (render-chart
       (if file file :display)
       ks cres
       "feature length"
       "Commulative Relative Entropy"
       (str "CRE(l, F/F^), len: " cnt
            " Fmax: " (round (ln cnt)))))))

(defn plot-re-pmf
  [nm-res  & {:keys [file name]}]
  (let [pmf-info (map (fn[[re p v]] [re (count v)]) (nm-res-dist nm-res))
        pmf-dist (into {} pmf-info)
        xs (sort (map first pmf-info))
        ys (map #(get pmf-dist % 0.0) xs)]
    (when file
      (write-plot-data-csv
       (fs/replace-type file ".csv")
       ["JSD" "Sq Count" "Name"] xs ys name))
    (render-chart
     (if file file :display)
     xs ys
     "JSD" "Sq count" (str name " Sq RE distribution")
     :series-label "sq/hbrid JSD PMF"
     :legend true)))

(defn plot-re-cdf
  [nm-res & {:keys [file name]}]
  (let [Fx (nm-res-cdf nm-res)
        step 0.005
        xs (for [i (range 200)] (* i step))
        ys (map Fx xs)]
    (when file
      (write-plot-data-csv
       (fs/replace-type file ".csv")
       ["JSD" "F(x)" "Name"] xs ys name))
    (render-chart
     (if file file :display)
     xs ys
     "JSD" "F(x)" (str name " FFP RE CDF plot")
     :series-label "sq/hbrid JSD CDF"
     :legend true)))


(defn plot-hit-dists
  [chartnm rna-name cnt-seqs ctx-delta res cutpt nms-stods
   & {:keys [file]}]
  (let [xs (range cnt-seqs)
        ys (map second nms-stods)
        out (if file (fs/join file (str chartnm "-hitonly.png")) :display)]
    (when file
      (write-plot-data-csv
       (fs/replace-type out ".csv")
       ["Seq" "JSD" "Name"] xs ys (->> rna-name (str/split #"-") first)))
    (render-chart
     out
     xs ys
     "Sequence" "Sq RE to Hybrid"
     (str rna-name " Hits only to Hybrid."
          " Res: " res ", Cutpt: " cutpt)
     :series-label "sq/hbrid-distance"
     :legend true)))

(defn plot-cand-dists
  [chartnm rna-name cnt-seqs ctx-delta res Mre CDp cutpt nms-stods
   & {:keys [file]}]
  (let [xs (range cnt-seqs)
        ys (map second nms-stods)
        out (if file (fs/join file (str chartnm "-candidates.png")) :display)]
    (when file
      (write-plot-data-csv
       (fs/replace-type out ".csv")
       ["Seq" "JSD" "Name"] xs ys (->> rna-name (str/split #"-") first)))
    (render-chart
     out
     xs ys
     "Sequence" "Sq RE to Hybrid"
     (str rna-name " Candidate SCCS. Cutpt " cutpt "\n"
          " Ctx Sz: " ctx-delta
          " Res: " res ", Mre: " Mre ", CD%: " CDp)
     :series-label "sq/hbrid-distance"
     :legend true)))




(defn re-cdf-cut [nm-res Dy Mre]
  (let [res (sort (map first (nm-res-dist nm-res))) ; only the SET of res
        Fx (nm-res-cdf nm-res)
        pts (sort (map second nm-res))
        cdf-cut (+ Dy 0.5) ; 0.5 = median of Fx by definition
        re-cutoff (reduce (fn[v re]
                            ;; Curry in re <= Max RE, to ensure we
                            ;; don't derail with CDF picking many bad,
                            ;; since nearly all are bad.  NOTE: that
                            ;; the default Mre was experimentally
                            ;; determined from various contexts across
                            ;; entire refseq.  NOTE 2: Since RE is
                            ;; unbounded, and JSD normalized, there
                            ;; can be arbitrarily many points between
                            ;; 0.9 and 1.0 (looonnnngggg tail...)
                            (if (and (<= re Mre) (<= (Fx re) cdf-cut)) re v))
                          0.0 res)
        ;;_ (print :re-cutoff1 re-cutoff :Fres (first res) "/ ")
        ;; A _single_ good score can occur an 'anomalous' number of times
        re-cutoff (let [fres (first res)]
                    (cond (not (zero? re-cutoff)) re-cutoff
                          (<= fres Mre) fres
                          :else 0.0))
        ;;_ (print :re-cutoff2 re-cutoff "/ ")
        ;; Round to nearest thousandth
        re-cutoff (/ (round (* 1000 (+ re-cutoff 0.001))) 1000.0)]
    (reduce (fn[cutpt re] (if (<= re re-cutoff) (inc cutpt) cutpt))
            0 (map second nm-res))))

(defn select-cutpoint
  [re-points & {:keys [Dy Mre] :or {Mre 0.935}}]
  (if (seq re-points)
    (re-cdf-cut re-points Dy Mre)
    (raise :type :no-points
           :msg "Cutpoint undefined on Empty set of relative entropy points!"
           :re-points re-points :Dy Dy :Mre Mre)))


(defn get-pos-neg-sets
  [nm-re-coll cutpoint & {:keys [ends] :or {ends :low}}]
  (let [total (count nm-re-coll)
        [good bad] (cond
                    (= ends :low)
                    [(take cutpoint nm-re-coll)
                     (drop cutpoint nm-re-coll)]

                    (= ends :high)
                    [(drop (- total cutpoint) nm-re-coll)
                     (take (- total cutpoint) nm-re-coll)]

                    :else ; :both
                    [(concat
                      (take cutpoint nm-re-coll)
                      (drop (- total cutpoint) nm-re-coll))
                     (take (- total cutpoint cutpoint)
                           (drop cutpoint nm-re-coll))])]
    [good bad]))


(defn res-word-size
  ""
  [seqs & {:keys [alpha xlate directed ldelta rdelta delta cnt limit crecut]
           :or {alpha (alphabet :rna)
                directed true cnt 5 limit 14 crecut 0.10}}]
  (let [cres (cre-samples seqs :delta delta
                          :xlate xlate :alpha alpha
                          :limit limit :cnt cnt)
        wz (reduce (fn [res v]
                     (/ (+ res
                           (some #(when (< (second %) crecut) (first %)) v))
                        2.0))
                   0.0 cres)]
    [(ceil wz) cres]))


(defn sto-re-dists
  ""
  [wz candidate-file sto-file
   & {:keys [refn delta xlate refdb stodb] :or {refn DX||Y delta 5000}}]

  (assert-files? [(fs/fullpath candidate-file) (fs/fullpath sto-file)])
  (let [cfile (fs/fullpath candidate-file)
        ctype (fs/ftype cfile)
        prot-name (->> cfile
                       fs/basename (str/split #"\.") first
                       (str/replace-re #"_" " "))

        entries (with-genome-db refdb
                  (get-entries cfile))
        sqs (with-genome-db refdb
              (->> entries
                   (#(get-adjusted-seqs % delta :ddel 0))
                   (map second)
                   (#(if xlate (seqXlate % :xmap xlate) %))))

        refsqs (with-genome-db stodb
                 (->> sto-file
                      get-entries
                      (#(get-adjusted-seqs % delta :ddel 0))
                      (map second)
                      (#(if xlate (seqXlate % :xmap xlate) %))))

        sto-hd (hybrid-dictionary wz refsqs)
        dicts (vfold #(probs wz %) sqs)
        stods (vfold #(refn % sto-hd) dicts)]

    [(sort-by second < (set (map (fn[en d] [en d]) entries stods)))
     prot-name
     (count sqs)]))


(defn compute-candidate-info
  ""
  [sto-file candidate-file delta run
   & {:keys [refn xlate alpha
             refdb stodb
             crecut limit wz scnt
             Dy Mre
             plot-cre plot-dists]
      :or {refn DX||Y alpha (alphabet :rna) crecut 0.10 limit 15 scnt 5}}]

  (let [[wz cres] (if wz
                    [wz]
                    (with-genome-db stodb
                      (res-word-size sto-file :delta delta
                                     :xlate xlate :alpha alpha
                                     :limit limit :crecut crecut :cnt scnt)))
        [nm-re-sq pnm sz] (sto-re-dists wz candidate-file sto-file
                                        :refn refn :xlate xlate
                                        :refdb refdb :stodb stodb
                                        :delta delta)
        ;; For hits, always use 0.9 on CDF (0.5 + Dy, For hits Dy hard 0.4)
        Dy (if (zero? delta)
             0.4
             (if Dy Dy
                 (case run 1 0.3, 2 0.25, 3 0.2, 4 0.1, 5 0.0, -0.1)))
        ;; For hits, max out JSD at 0.7, in practice => CDF determines cut
        Mre (if (zero? delta)
              0.7
              (if Mre Mre
                  (case run 1 0.953, 2 0.94, 3 0.935, 4 0.93, 0.927)))

        cutpt (select-cutpoint nm-re-sq :Dy Dy :Mre Mre)
        [good bad] (get-pos-neg-sets nm-re-sq cutpt)

        chartnm (->> sto-file
                     fs/basename (str/split #"\.") first
                     (str/replace-re #"_" "-"))]

    (when (and plot-cre cres) (plot-cres cres))
    (when plot-dists
      (if (zero? delta)
        (plot-hit-dists chartnm
                        pnm sz delta wz cutpt
                        nm-re-sq :file plot-dists)
        (plot-cand-dists chartnm
                         pnm sz delta wz Mre (+ Dy 0.5) cutpt
                         nm-re-sq :file plot-dists)))
    [good bad cutpt nm-re-sq]))


(declare get-hitonly-final-names)

(defn compute-candidate-sets
  "Compute the positive and negative candidate sets for 'hitonly' and
  'genomic context' search candidates.  The search candidates are
  given in CANDIDATE-FILE; typically a csv file from a GEN-CSV config
  directive, but this can be any 'entry' type file: csv, sto, ent,
  fna, etc.  SEED-SEQS gives the set of 'original'/originating
  sequences from which the candidates were derived.  Typically this is
  a sto file which was used to compute and calibrate a CM and used to
  search a sequence database (std Infernal pipeline via config
  directives), but can be any 'originating' set of sequences as given
  directly or by 'entry' files (sto, ent, fna, etc).

  RUN is an integer indicating
  "
  [seed-seqs candidate-file run delta
   & {:keys [refn xlate alpha refdb stodb crecut limit scnt Dy
             Mre plot-cre plot-dists]
      :or {refn DX||Y alpha (alphabet :rna) crecut 0.10 limit 15 scnt 5}}]

  (let [refdb (@genome-db-dir-map (if refdb refdb :refseq58))
        stodb (@genome-db-dir-map (if stodb stodb :refseq58))

        [first-phase-entry-file final-entry-file]
        (get-hitonly-final-names candidate-file seed-seqs)

        first-phase-entry-file (fs/fullpath first-phase-entry-file)
        first-phase-neg-file (fs/replace-type
                              first-phase-entry-file
                              (str "-neg." (fs/ftype first-phase-entry-file)))

        final-entry-file (fs/fullpath final-entry-file)
        final-bad-entry-file (fs/replace-type
                              final-entry-file
                              (str "-bad." (fs/ftype final-entry-file)))
        [rna-only-good
         rna-only-bad
         cutpt1
         rna-nm-re-sq] (compute-candidate-info
                        seed-seqs candidate-file 0 run
                        :refn refn :xlate +RY-XLATE+ :alpha alpha
                        :refdb refdb :stodb stodb
                        :limit limit :wz 6
                        :Dy Dy :Mre Mre
                        :plot-cre plot-cre :plot-dists plot-dists)

        _ (->> rna-only-good (map first)
               (#(gen-entry-file % first-phase-entry-file)))
        _ (->> rna-only-bad (#(gen-entry-nv-file % first-phase-neg-file)))

        [good bad
         cutpt2
         nm-re-sq] (compute-candidate-info
                    seed-seqs first-phase-entry-file delta run
                    :refn refn :xlate xlate :alpha alpha
                    :refdb refdb :stodb stodb
                    :crecut crecut :limit limit :scnt scnt
                    :Dy Dy :Mre Mre
                    :plot-cre plot-cre :plot-dists plot-dists)]

    (->> good (#(gen-entry-nv-file % final-entry-file)))
    (->> bad (#(gen-entry-nv-file % final-bad-entry-file)))
    #_(with-genome-db refdb
        (entry-file->fasta-file final-entry-file))

    [[good bad cutpt2]
     [rna-only-good rna-only-bad cutpt1]
     rna-nm-re-sq
     nm-re-sq
     [final-entry-file final-bad-entry-file]]))


(defn get-hitonly-final-names
  "Generate the canonical names for the output first phase 'hitonly',
   and 'final' entry files resulting from an FFP
   compute-candidate-sets selection from cmsearch targets.  CF is the
   candidate file name of the filtered results of the base cmsearch
   output.  Typically the csv file matching a cmsearch.out.
  "
  [cf seed-name]
  (let [prefix (and (string? seed-name)
                    (->> seed-name fs/basename (str/split #"\.") first))
        cfdir (fs/dirname cf)
        ;; This is not done well - can be much cleaner/simpler...
        cfbase (if (not prefix)
                 (fs/basename cf)
                 (str/join
                  "." (cons prefix
                            (->> cf fs/basename (str/split #"\.") rest))))
        cf (fs/join cfdir cfbase)
        suffix (first (re-find #"(\.[a-z]+|)\.cmsearch\.(csv|out|ent)$" cf))
        suffix-re (re-pattern suffix)
        hitonly (str/replace-re suffix-re "-hitonly.ent" cf)
        final (str/replace-re suffix-re "-final.ent" cf)]
    [hitonly final]))


(defn get-saved-ctx-size
  "If we have previously calculated the context size for sequences in
   STO, use that directly.  Previously calculated context sizes are
   recorded in sto on a #=GF line with subtype CTXSZ, followed by the
   size (an integer)
  "
  [sto]
  (let [l (->> sto io/read-lines
               (drop-until #(re-find #"^(#=GF\s+CTXSZ\s+[0-9]+|NC)" %))
               first (re-find #"CTXSZ\s+([0-9]+|)") second)]
    (if l (Integer. l) nil)))

(defn score-ctx
  "Power law scoring function for context sizes based on hit context
   delta relative entropies.  The entropies and lengths are encoded in
   CSV format in the file CTXSZ-CSV as computed and written by
   hit-context-delta.  Returns the 'best' 10 scores (delta lengths).

   hit-context-delta by default returns the first, i.e., 'best', such
   score as its last action when doing a full computation of delta
   contexts.
  "
  [ctxsz-csv]
  (->> ctxsz-csv
       slurp csv/parse-csv butlast rest
       (map (fn[[lstg restg nm]] [(Integer. lstg) (Float. restg)]))
       (map (fn[[l re]] [l (expt re (-> l log))]))
       (sort-by second)
       (take 10)))

(defn hit-context-delta
  "Find the genomic context delta for the RNA sequences in STO (a sto
   file) based on conserved information content of the downstream (3'
   'trailer') sequence from the RNA locations.  SCNT is the sample
   count from the sto sequences to use for computation of optimial
   word size.

   MINDELTA is the minimal downstream amount to check - effectively
   the same as the starting delta to use for information content
   calculations.

   STODB is the reference genome database to use and must be a keyword
   that is recognized by GENOME-DB-DIR-MAP.  If not given, defaults to
   the current default refseq genomes (at time of writing, :refseq58).

   PLOT indicates the directory in which to save the context delta
   entropy charts, or nil/not given if none should be generated.

   hit-context-delta proceeds as follows:

   For each delta in (take 81 (iterate #(+ % 20) mindelta))
     get coll of downstream seqs for delta and coordinates of sto seqs
     compute the optimial word size wz for this set based on scnt
     use wz to get prob dists for each sq and compute centroid dist
     compute JSD of each sq to centroid
     compute mean of JSDs

   Return max delta for min over all JSD points
  "
  [sto & {:keys [plot scnt mindelta stodb] :or {scnt 7 mindelta 200}}]

  (let [gfcsz (get-saved-ctx-size sto)
        stodb (@genome-db-dir-map (if stodb stodb :refseq58))]
    (cond
     ;; Saved in sto from some previous run
     gfcsz [(list [gfcsz 0.0]) true]

     ;; If only one seq, all deltas give JSD 0, so mindelta
     (= 1 (count (doall (read-seqs sto :info :name))))
     [(list [mindelta 0.0]) false]

     :else ;; compute and select max-min over delta range
     (let [getfile (fn [dir suffix]
                     (->> sto fs/basename
                          (#(fs/replace-type % suffix))
                          (fs/join dir)))
           pts (vfold (fn[i]
                        (->> (compute-candidate-info
                              sto sto
                              (+ mindelta (* i 20)) 1
                              ;; Force to 0.9 CDF and 0.99 Max RE
                              :Dy 0.4 :Mre 0.99
                              :refn jensen-shannon
                              ;;:xlate +RY-XLATE+ :alpha ["R" "Y"]
                              :refdb stodb :stodb stodb
                              :crecut 0.01 :limit 19 :scnt scnt
                              :plot-dists false)
                             first (map second) mean))
                      (range 81))
           ms (map #(min %1 %2) pts (drop 1 pts))
           szs (map #(+ mindelta (* 20 %)) (range (count ms)))
           nm (->> sto fs/basename (str/split #"-") first)
           ptfile (getfile (if plot plot (fs/dirname sto)) "-ctxsz.csv")
           out (if plot (getfile plot "-ctxsz.png") :display)]

       (when (and plot (not (fs/exists? plot))) (fs/mkdirs plot))

       (when plot
         (render-chart
          out
          szs ms
          "Size X 20" "RE/JSD" "RE to subseq"
          :series-label "Sub Seq Size"
          :legend true))
       (write-plot-data-csv
        ptfile ["Extension" "JSD mean" "Name"] szs ms nm)
       [(score-ctx ptfile) false]))))

(defn save-hit-context-delta
  "Compute the hit context delta for the sequences in stofile (see
   hit-context-delta) and save the result in stofile (using our std
   convention of '#=GF CTXSZ ...' GF line).  The original stofile is
   renamed to {stofile}-bkup.sto.  ARGS are any ancillary arguments
   taken by hit-context-delta and are simply passed on through.
  "
  [stofile & args]
  (let [[ctxsz found] (apply hit-context-delta stofile args)
        [hd nm-sq-pairs ss-lines] (join-sto-fasta-lines stofile "")
        ctxsz-gf (str "#=GF CTXSZ " ctxsz)]
    (when (not found)
      (fs/rename stofile (fs/replace-type stofile "-orig.sto"))
      (write-sto
       stofile (take 2 hd) (cons ctxsz-gf (drop 2 hd)) nm-sq-pairs ss-lines))))


(defn hit-context-delta-db
  "Find the genomic context delta for the RNA sequences in STO (a sto
   file) based on the GENE (symbolic name given as a strin, for
   example \"rpsD\".  Context is found by using annotation information
   in the refseq database to find the gene location, size and relative
   position to the RNA location.  MARGIN is an integer used to give an
   adjustment to the base delta found (typically left at 0).  CNT
   provides the number of random selections from the RNAs in STO to
   use for search.

   Limitation: Requires that all RNAs in STO be from the currently
   used NCBI refseq microbial GenBank files used to build the current
   database.  Hence, requires annotations to be used.

   Generally, this is no longer the method of choice; use
   hit-context-delta which does not require annotations or alignment
   information.  Directly using conserved information content as
   determined by various entropy measures.
  "
  [sto & {:keys [gene cnt margin] :or {cnt 5 margin 0}}]

  {:pre [(string? gene)]}
  (let [sample (random-subset (read-seqs sto :info :name) cnt)
        nms-loci (map #(let [[nm [s e] sd] (entry-parts %)] [nm s e sd]) sample)
        features (map (fn[[nm s e sd]]
                        [[nm s e sd]
                         (edu.bc.bio.gaisr.db-actions/hit-features-query
                          nm s e)])
                      nms-loci)
        nms-lens
        (->> features
             (map (fn[[[nm s e sd :as entry] ctx]]
                    [entry
                     (ffirst
                      (keep (fn[m]
                              (let [nvs (m :nvs)
                                    x (keep #(when (and (= (% :name) "gene")
                                                        (= (% :value) gene))
                                               ;; -1 sd => hit end - ctx start
                                               ;;  1 sd => ctx end - hit start
                                               (let [{cs :start
                                                      ce :end
                                                      csd :strand}
                                                     (first (m :locs))
                                                     len (inc (if (= 1 csd)
                                                                (- ce s)
                                                                (- e cs)))]
                                               [len %]))
                                            nvs)]
                                (when (and (in (m :sftype) ["CDS" "gene"])
                                           (seq x))
                                  (first x))))
                            ctx))]))
             (filter second))
        base (+ margin
                (if (not (seq nms-lens))
                  (hit-context-delta-db sto :gene gene :cnt cnt)
                  (reduce (fn[sz [nm s]]
                            (first (div (+ sz s) 2)))
                          (-> nms-lens first second) (rest nms-lens))))]
    (* (floor (+ (/ base 100) 1)) 100)))




(defn get-deltas
  [sto]
  (let [ctxsz (hit-context-delta sto)]
    (take 3 (iterate #(+ % 250) ctxsz))))

(defn get-k
  [sto]
  (let [cnt (-> sto (read-seqs :info :name) count)]
    (if (< cnt 15)
      [2 3]
      (mapv #(int (ceil (* % cnt))) [0.1 0.12 0.17]))))

(defn get-krange [kinfo seqcnt]
  (cond
   (nil? kinfo) [4 (long (->> seqcnt (* 0.1) ceil))]
   (vector? kinfo) (->> kinfo (mapcat #(if (vector? %) (apply range %) [%])))
   :else [kinfo]))

(defn krnn-seqs-clust
  ""
  [seqents & {:keys [xlate alpha delta crecut limit kinfo vindex]
              :or {alpha (alphabet :rna) vindex clu/S-Dbw-index}}]
  {:pre [(or (and (vector? kinfo)
                  (reduce (fn[b x]
                            (and b (or (integer? x) (vector? x)))) true kinfo))
             (integer? kinfo)
             (nil? kinfo))]}

  (let [seqents (if (coll? seqents) seqents (read-seqs seqents :info :name))
        seqcnt (count seqents)
        krange (get-krange kinfo seqcnt)

        ;; Get entries and their delta adjusted sequences and Goedel
        ;; number them for efficient map key access.
        entries  (map (fn[i ent] [i ent]) (iterate inc 0) seqents)
        seqs (get-adjusted-seqs (map second entries) delta)
        entries (into {} entries)
        coll (->> seqs
                  (map (fn[[e s]] [e (if xlate (seqXlate s :xmap xlate) s)]))
                  (mapv (fn[i ent-sq] [i ent-sq]) (iterate inc 0)))

        ;; Get the resolution window size for sequence words
        ;; (features), which controls vocabulary distribution content
        ;; (dictionaries), pmfs, and RE.
        _ (println :start-word-res-size (str-date))
        [wz] (res-word-size (map first seqs) :delta delta
                            :xlate xlate :alpha alpha
                            :crecut crecut :limit limit)

        ;; Precompute all the resulting dictionaries of sequences with
        ;; word size.  This is a _vector_ of said dictionaries and is
        ;; naturally indexed by the numbering of the entries.
        _ (println :start-coll-ffps (str-date))
        coll-ffps (mapv (fn[[i [e sq]]] (probs wz sq)) coll)

        ;; Distance function that uses above precomputed distributions
        ;; as inputs keyed off entry/seq numbering.  Just call
        ;; coll-ffps on the numberings (i and j) of the inputs.  Each
        ;; arg has shape [i [ent-i sq-i]]; we only need i.
        distfn (fn[[x _x] [y _y]]
                 (jensen-shannon (coll-ffps x) (coll-ffps y)))

        ;; Compute distance matrix for knn graph computation.  keyfn
        ;; is first of input elements which gives the numbering for an
        ;; entry.  So, matrix is a map with keys [x y], x & y
        ;; associated numbers of coll entries
        _ (println :start-dm-computation (str-date))
        keyfn first
        dm (clu/dist-matrix distfn coll :keyfn keyfn)


        ;; Distance function for S-Dbw cluster validity measure.
        ;; Every 'point' here should be a word dictionary as seq-clus
        ;; (see below) is precomputed to be such.
        distfn2 (fn[l r] {:pre [(and (map? l) (map? r))]}
                  (jensen-shannon l r))

        ;; Averaging ('mean') function for S-Dbw validity measure.
        ;; This computes the 'hybrid' (minimized entropy) dictionary -
        ;; a 'centroid' dictionary.
        avgfn (fn
                ([sqs]
                   (hybrid-dictionary wz sqs))
                ([x & xs]
                   (hybrid-dictionary wz (cons x xs))))

        kcoll (map keyfn coll)]

    (println :wz wz :krange krange (str-date))
    (for [k krange
          :let [[krnngrph rnncntM knngrph]
                (clu/krnn-graph k #(get dm [%1 %2]) kcoll)]]
      (let [_ (println :start-clustering :k k (str-date))
            clusters (->> (clu/split-krnn k krnngrph rnncntM knngrph)
                          ;;(#(do (prn :G>k/G<k %) %))
                          (map #(gr/tarjan (keys %) %))
                          ;;(#(do (prn :sccs %) %))
                          (#(clu/refoldin-outliers
                             krnngrph (first %) (second %) knngrph))
                          vec)
            ent-clus (let [coll (into {} coll)]
                       (mapv (fn[scc]
                               (map (fn[x]
                                      [(entries x) (-> x coll first)])
                                    scc))
                             clusters))
            seq-clus (let [coll (into {} coll)]
                       (mapv (fn[scc]
                               (let [v (vfold
                                        (fn[x] (coll-ffps x))
                                        (vec scc))]
                                 [(avgfn v) v]))
                             clusters))
            _ (println :end-clustering :start-S-Dbw (str-date))]
        [(if vindex (vindex distfn2 (vec seq-clus) :avgfn avgfn) 0.0)
         ent-clus k]))))

(defn split-sto
  ""
  [entfile & {:keys [delta xlate alpha crecut limit kinfo vindex clu-dir]
              :or {alpha (alphabet :rna) vindex clu/S-Dbw-index}}]
  (let [ents-sqs (read-seqs entfile :info :both)
        entries (map first ents-sqs)
        entsq-map (into {} ents-sqs)
        lines (io/read-lines entfile)
        headers (take 2 lines)
        footers (->> lines
                     (drop-while #(or (= % "") (re-find #"^#" %)))
                     (drop-while #(not (re-find #"^#" %))))

        gen-sto (fn[fname ents]
                  (io/with-out-writer fname
                    (doseq [l headers] (println l))
                    (println)
                    (doseq [[e s] (map #(do [% (entsq-map %)]) ents)]
                      (cl-format true "~A~40T~A~%" e s))
                    (doseq [l footers] (println l))))

        basedir (fs/dirname entfile)
        name (->> entfile fs/basename (str/split #"\.") first)
        clu-dir (if clu-dir clu-dir (fs/join basedir (str "CLU-" name)))
        _ (when (not (fs/exists? clu-dir)) (fs/mkdirs clu-dir))
        clud-dir (fs/join clu-dir (str "d" delta))
        _ (when (not (fs/exists? clud-dir)) (fs/mkdir clud-dir))

        ;; Compute cluster sets for each k; sort by index score, so
        ;; that 'best' is first cluster set
        clu-info (->> (krnn-seqs-clust
                       entries
                       :delta delta
                       :xlate xlate :alpha alpha
                       :crecut crecut :limit limit
                       :kinfo kinfo
                       :vindex vindex)
                      (sort-by first <))

        ;; Get 'best' k and corresponding 'best' clusters as sets of
        ;; the original entries
        k (->> clu-info first third)
        ent-clus (->> clu-info first second (map #(map first %)))

        fs (->> ent-clus
                (map (fn[i ents]
                       (gen-entry-file
                        ents (fs/join clud-dir (str "clu-k" k "-" i ".ent"))))
                     (iterate inc 1))
                doall)
        stos (->> ent-clus
                  (map (fn[i ents]
                         (let [f (str "clu-k" k "-" i ".sto")]
                           (gen-sto (fs/join clud-dir f) ents)))
                       (iterate inc 1))
                  doall)]
    [(map (fn[[s ents k]] [s k (count ents) (map count ents)]) clu-info) fs]))


(defn aggregate-sccs-ents
  "Aggregate the resulting SCCS output entry files for a cluster set.
   The precondition is that a given set of sequences (typically from a
   sto) were split using clustering via split-sto; and the results of
   these were each run through SCCS and the outputs (candidate +/- and
   hitonly +/- entry files of sequence sets) placed in CSV-DIR.
   CSV-DIR should only contain the relevant entry files. OUT-DIR is
   where the aggregated (via tools/entry-file-union) entry files are
   to be placed and AGGR is the prefix for the names of the aggregated
   files.
  "
  [csv-dir csv out-dir aggr]
  (let [csv (if (not= (->> csv-dir fs/split second) (->> csv fs/split second))
              (fs/join csv-dir csv) csv)
        csv (->> csv fs/glob first)
        aggfn (fn[tgt-glob out-file]
                (-> (apply tools/entry-file-union true
                           (fs/glob tgt-glob))
                    (gen-entry-file out-file)))
        negfn (fn[posf out-file]
                (-> (tools/entry-file-difference
                     true csv posf)
                    (gen-entry-file out-file)))
        posf (fs/join out-dir (str aggr "-pos.ent"))
        pos-hitonly-f (fs/join out-dir (str aggr "-hitonly.ent"))]

    (aggfn
     (str csv-dir "/*hitonly.ent") pos-hitonly-f)
    (negfn
     pos-hitonly-f (fs/join out-dir (str aggr "-hitonly-neg.ent")))
    (aggfn
     (str csv-dir "/*final.ent") posf)
    (negfn
     posf (fs/join out-dir (str aggr "-neg.ent")))))


(defn sto-ctx-merge
  "Take a set of clusters, as output by split-sto from an original
   input sto of presumed multiple genomic contexts, compute the best
   context delta for each, then merge clusters according to equal
   context deltas.  The output is a new set of stos reflecting the
   merge (this could be the same as the input if no merge is
   possible).

   STOS is the set of clusters as the stos generated by split-sto.
   BASE is a directory file specification for where to put the merged
   resulting stos, with the default the location of the input stos.
   PLOT indicates the directory to save the context delta entropy
   charts, or nil/not given if none should be generated.  MINDELTA is
   the minimum delta for hit-context-delta and SCNT is the sampling
   count for hit-context-delta.

   NOTE: it is _not_ necessarily true that the clusters resulting from
   merges reflect contexts that should 'go together'.  This is
   especially true for significantly diverse contexts.  Hence
   sto-ctx-merge likely should only be used on known similar contexts,
   such as those for S6.
  "
  [stos & {:keys [base plot scnt mindelta stodb] :or {scnt 7 mindelta 200}}]

  (let [ctx-info (mapv (fn[sto]
                         [sto
                          (-> sto (read-seqs :info :name) count)
                          (first (hit-context-delta
                                  sto :plot plot :scnt scnt
                                  :mindelta mindelta :stodb stodb))])
                       (sort stos))
        sto-grps (group-by third ctx-info)
        base (if base base (-> stos first fs/dirname))]
    (doseq [[sz stos] sto-grps]
      (let [stos-nms-sqs (map #(second (join-sto-fasta-lines (first %) ""))
                              stos)
            nms-sqs (apply concat (map #(map (fn[[nm [id sq]]] [nm sq]) %)
                                       stos-nms-sqs))
            [hd _ ss-lines] (join-sto-fasta-lines (ffirst stos) "")

            name-prefix (str/join
                         "-" (butlast (str/split
                                       #"-" (-> stos ffirst fs/basename))))
            clus (str/join "-" (map #(->> % (str/split #"-")
                                          last (str/split #"\.") first)
                                    (map #(-> % first fs/basename) stos)))
            merge-name (fs/join base (str name-prefix "-" clus ".sto"))]
        (write-sto merge-name hd [(str "#=GF CTXSZ " sz)] nms-sqs ss-lines)))))




;;; ------------------ Sensitivity / Accuracy Measures ---------------------;;;
;;;
;;; RE score vs EV scoring - measure of manual reduction
;;;
;;; False positives, False negatives
;;;
;;; Receiver Operating Characteristic (ROC) scores and curves
;;;
;;; ------------------------------------------------------------------------;;;

"/home/kaila/Bio/STOfiles/031113/AUTO/X/S15/CSV/0R1K/S15-auto-0.sto.all-custom-db-final.ent"

"/home/kaila/Bio/STOfiles/031113/AUTO/X/S15/CSV/0R1K/S15-auto-0.sto.all-custom-db.cmsearch.csv"

(defn sccs-ev-good
  [sccs-pos-ent cmsearch-csv]
  (let [sccs-map (->> sccs-pos-ent
                      slurp clojure-csv.core/parse-csv butlast
                      (map #(do [(first %) (->> % second str/trim Double.)]))
                      (reduce (fn[M [k v]] (assoc M k (conj (M k []) v))) {}))
        ev-map (->> cmsearch-csv
                    slurp clojure-csv.core/parse-csv rest butlast
                    (map #(let [[s e] [(Long. (nth % 3)) (Long. (nth % 4))]
                                [s e std :as x] (if (< e s) [e s -1] [s e 1])
                                entry (apply make-entry (first %) x)]
                            [entry (Double. (nth % 9))]))
                    (reduce (fn[M [k v]] (assoc M k v)) {}))
        good (->> (reduce (fn[M [k v]]
                            (if (contains? M k) (assoc M k (conj (M k) v)) M))
                          sccs-map ev-map)
                  (filter (fn[[k v]] (> (count v) 1)))
                  (sort-by (fn[[k v]] (first v)) <)
                  (take-until (fn[[k v]] (> (first v) 0.94)))
                  (into {}))
        byev (->> (reduce (fn[M [k v]]
                            (if (contains? M k) (assoc M k (conj (M k) v)) M))
                          sccs-map ev-map)
                  (filter (fn[[k v]] (> (count v) 1)))
                  (sort-by (fn[[k v]] (second v)) <))
        evset (first
               (reduce (fn[[M1 M2] [k [sv ev]]]
                         (cond
                          (empty? M2) [M1 M2]
                          (contains? M2 k) [(assoc M1 k [sv ev]) (dissoc M2 k)]
                          :else [(assoc M1 k [sv ev]) M2]))
                       [{} good] byev))]
    {:sccs good :ev evset}))








(comment


(def L20-auto
     (compute-candidate-sets
      "/home/kaila/Bio/STOfiles/031113/AUTO/B/L20-auto-3.sto"
      "/home/kaila/Bio/STOfiles/031113/AUTO/B/CSV/Pass-3/L20-auto-3.sto.Assortprot1.fna.cmsearch.csv"
      3 1200
      :refn jensen-shannon
      :xlate +RY-XLATE+ :alpha ["R" "Y"]
      :crecut 0.01 :limit 19
      :plot-dists true))



(def bigrun2-info
     (compute-candidate-sets
      "/home/kaila/Bio/Tests/JSA/RNA_00012.sto"
      "/home/kaila/Bio/Tests/RNA_00012.sto.Assortprots2.fna.cmsearch.csv"
      "/home/kaila/Bio/Tests/RNA_00012.sto.Assortprots2-hitonly.ent"
      "/home/kaila/Bio/Tests/RNA_00012.sto.Assortprots2-final.ent"
      (hit-context-delta "/home/kaila/Bio/Tests/RNA_00012.sto" :gene "rpsO")
      :refn jensen-shannon :xlate +RY-XLATE+ :alpha ["R" "Y"] :crecut 0.01 :limit 19
      :order :up :ends :low
      :plot-cre false :plot-dists true :cutoff 33/100))

(def rfam-00504-AP2-test
     (compute-candidate-sets
      "/home/jsa/Bio/FreqDicts/NewRFAM/STOS/RF00504-seed-NC.sto"
      "/home/jsa/Bio/FreqDicts/NewRFAM/STOS/CSV/RF00504-seed-NC.sto.Assortprots2.fna.cmsearch.csv"
      "/home/jsa/Bio/FreqDicts/NewRFAM/STOS/RF00504-AP2-hitonly.ent"
      "/home/jsa/Bio/FreqDicts/NewRFAM/STOS/RF00504-AP2-final.ent"
      (hit-context-delta "/home/jsa/Bio/FreqDicts/NewRFAM/STOS/RF00504-seed-NC.sto" :gene "gcvT")
      :refn jensen-shannon :xlate +RY-XLATE+ :alpha ["R" "Y"] :crecut 0.01 :limit 19
      :order :up :ends :low
      :plot-cre false :plot-dists true :cutoff 37/100))

)



#_(def sample-metag-cres
     (->> (fs/directory-files "/data2/Bio/MetaG1/FastaFiles" ".fa")
          first read-seqs
          (take 20)
          (map #(pxmap (fn[k] [k (CREl k %) (count %)]) 2 (range 3 16)))))

#_(doseq [cres (take 3 sample-metag-cres)]
  (let [ks (map first cres)
        cnt (third (first cres))
        cres (map second cres)]
    (incanter.core/view
     (incanter.charts/scatter-plot
      ks cres
      :x-label "feature length"
      :y-label "Commulative Relative Entropy"
      :title (str "CRE(l, F/F^), len: " cnt
                  " Fmax: " (round (ln cnt)))))))

#_(doseq [cres foocres]
  (let [ks (map first cres)
        cnt (third (first cres))
        cres (map second cres)]
    (incanter.core/view (incanter.charts/scatter-plot
                         ks cres
                         :x-label "feature length"
                         :y-label "Commulative Relative Entropy"
                         :title (str "CRE(l, F/F^), len: " cnt
                                     " Fmax: " (round (ln cnt)))))))

#_(def count-map
     (reduce (fn[m file]
               (reduce (fn[m sq]
                         (let [cnt (count sq)]
                           (assoc m cnt (inc (get m cnt 0)))))
                       m (read-seqs file)))
             {} (fs/directory-files "/data2/Bio/MetaG1/FastaFiles" ".fa")))


#_(def count-maps
       (map #(do [(fs/basename %)
                  (reduce (fn[m sq]
                            (let [cnt (count sq)]
                              (assoc m cnt (inc (get m cnt 0)))))
                          {} (read-seqs %))])
            (fs/directory-files "/data2/Bio/MetaG1/FastaFiles" ".fa")))

#_(sort-by
   first
   (map #(do [(first %) (->> % second (sort-by first >)
                             ((fn[sq]
                                [(take 5 sq) (drop (- (count sq) 5) sq)])))])
        count-maps))

