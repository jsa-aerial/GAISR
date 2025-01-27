;;--------------------------------------------------------------------------;;
;;                                                                          ;;
;;                                  C O R E                                 ;;
;;                                                                          ;;
;;                                                                          ;;
;; Copyright (c) 2011-2013 Trustees of Boston College                       ;;
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

(ns edu.bc.bio.gaisr.core

  "Main name space.  Requires system parts and holds main startup
   function"

  (:require

   clojure.string

   edu.bc.utils
   edu.bc.utils.probs-stats
   edu.bc.utils.trees
   edu.bc.utils.bktrees
   edu.bc.utils.graphs
   edu.bc.utils.clustering
   edu.bc.job-utils
   edu.bc.net-utils

   edu.bc.bio.seq-utils
   edu.bc.bio.sequtils.files
   edu.bc.bio.sequtils.tools
   edu.bc.bio.sequtils.info-theory
   edu.bc.bio.sequtils.dists
   edu.bc.bio.canned-jobs
   edu.bc.bio.db-downloads

   edu.bc.bio.gaisr.db-actions  ; Main database setup and access
   edu.bc.bio.sequtils.sccs     ; bad dependency: db-actions/hit-features-query
   edu.bc.bio.gaisr.operon-ctx  ; Initial Hit (blast, ...) operon ctx
   edu.bc.bio.gaisr.pipeline    ; pipeline functions and framework
   edu.bc.bio.gaisr.post-db-csv ; Post database query processing
   edu.bc.bio.gaisr.new-rnas    ; New (Meyer Lab Found) RNA processing
   edu.bc.bio.gaisr.actions     ; web services implementation
   edu.bc.bio.gaisr.www         ; web services REST interface
   ))


(defn -main [& args]
  )
