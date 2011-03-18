(defproject r0man/cascading.jdbc "1.2"
  :java-source-path "src/java"
  :java-fork "true"
  :javac-debug "true"
  :dependencies [[cascading/cascading-core "1.2-wip-63" :exclusions [org.codehaus.janino/janino]]]
  :repositories {"conjars" "http://conjars.org/repo/"}
  :dev-dependencies [[org.apache.hadoop/hadoop-core "0.20.2-dev"]])
