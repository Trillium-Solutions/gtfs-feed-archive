#!/bin/bash
# java -jar ~/clojure/gtfs-feed-archive/target/gtfs-feed-archive-0.1.2-standalone.jar -o output/ -c cache/ -i ~/clojure/gtfs-feed-archive/resources/oregon_public_gtfs_feeds.csv -a 
#version="0.1.2"

work_dir=`pwd`
# csv=try_gtfs_feeds.csv
version="0.1.3.1"
jar_file=~/clojure/gtfs-feed-archive/target/gtfs-feed-archive-$version-standalone.jar
project_directory=~/clojure/gtfs-feed-archive/

for c in $work_dir/try_csv_input/*.csv; do
    csv_opts="$csv_opts -i $c "
done
 
echo csv_opts: $csv_opts

# default_options="-a"
default_options=""
# java -jar $jar_file -o output-test/ -c cache/ -i $csv "$@"

(cd  $project_directory;
 lein run -o $work_dir/output-test/ -c $work_dir/cache/ $csv_opts "$@")

