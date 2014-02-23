#!/usr/bin/perl -Wall
#
# Import GTFS data into a spatial postgresql database, using the same format as
# the api-gtfs (https://github.com/alexmuro/api-gtfs) application expects.
#
#
# Copyright (c) 2014 Ed Groth http://ed-groth.com
# 
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
# 
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.
# 
# Contributers:
# 
# Thanks to Alex Muro (https://github.com/alexmuro) for code samples and
# advice, and for creating the fantastic Geo GTFS API and visualization tools.
# 
# Installation:
#
# Edit the global variables below to specify your database connections
# settings, and the location of the GTFS files you want to import. 
# 
# The GTFS directory layout it expects is:
#
# gtfs-data-dir/
# gtfs-data-dir/city1/
# gtfs-data-dir/city1/city1_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city1/city1_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city1/city1_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city2/
# gtfs-data-dir/city2/city2_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city2/city2_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city2/city2_YYYYMMDD_hhmm.zip
# gtfs-data-dir/city2/city2_YYYYMMDD_hhmm.zip
# 
# (Handily, this is the directory layout of archives created by the
# GTFS feed archive tool: https://github.com/ed-g/gtfs-feed-archive)
#
# The name of the zip file is abritrary, but keep in mind that is what we use
# for the datafile field of the database, so don't go nuts.
# 
# If you embed a last-modified date within the GTFS zip filename, your file
# names will match those used by gtfs-feed-exhange.com and at some point we might
# parse out those dates to set fields in the database, and allow versioning of
# each agency's data. Currently it just makes the import tool feel good, knowing 
# that all is organized within its universe.
#
#  YYYY is year  (GMT+0)
#  MM is month   (GMT+0)
#  DD is day     (GMT+0)
#  hh is hours   (GMT+0)
#  mm is minutes (GMT+0)
#
# By the way, you'll need to enable PostGIS extensions in whichever
# postgres database you're using here. For postgis 2.1 and newer, run
# the following commands as a database superuser:
# 
# CREATE EXTENSION postgis;
# CREATE EXTENSION postgis_topology;
# CREATE EXTENSION fuzzystrmatch;
# CREATE EXTENSION postgis_tiger_geocoder;
#
# Also you will need to install a copy of the gtfsdb tool which is conveniently
# available from http://gtfsdb.com. Make sure to include the geoalchemy postgis
# extensions. FYI as of this writing (Februrary 2014) geoalchemy 0.7.4 requires
# sqlalchemy 0.8.4, sqlalchemy 0.9.x does not work.


use DBI;

# Should we modify the sails_agency table?
# local ($create_api_gtfs_tables) = True;

#### MODIFY THESE GLOBAL VARIABLES TO POINT AT YOUR FILES AND DATABASE
#
# TODO: create command line arguments for all of these, instead.

# local ($mode) = "testing";
local ($mode) = "production";

## local ($mode) = "production";
local ($db_host) = None;
local ($db_user) = None;
local ($db_pass) = None;
local ($db_name) = None;
local ($data_dir) = None; ## directory with GTFS data.
local ($create_api_gtfs_tables) = None;

## PRODUCTION
if ($mode eq "production") {
    $db_host = "localhost";
    $db_user = "gtfs_api";
    $db_pass = "********";
    $db_name = "gtfs_api";
    $data_dir = "$ENV{HOME}/geo-gtfs-api/gtfs-update/local-gtfs";
    $create_api_gtfs_tables = 1;
} elsif ($mode eq "testing") {
    $db_host = "localhost";
    $db_user = "gtfs_test";
    $db_pass = "********";
    $db_name = "gtfs_test";
    $data_dir = "$ENV{HOME}/geo-gtfs-api/Oregon-GTFS-feeds-2014-02-17Z/feeds";
    $create_api_gtfs_tables = 1;
}

#### ^^ MODIFY THESE GLOBAL VARIABLES ^^


# local (@psql_cmds) = ("psql", "-U", $db_user, "-h", $db_host, "-d", $db_name);

local ($dbh) = DBI->connect("dbi:Pg:dbname=$db_name;host=$db_host;",
			    $db_user, $db_pass);

sub test_dbh {

    return unless $create_api_gtfs_tables;

    # my ($sth) = $dbh->prepare("SELECT * from NOW();");
    # my ($sth) = $dbh->prepare("SELECT * from BLARHGSNOW();");

    my ($sth) = $dbh->prepare("SELECT * from sails_agency;");

    my ($rv) = $sth->execute;
    
    if ($rv) {
	print "sth rows: " . $sth->rows() . "\n";
	print "rv:  " . $rv . "\n";
	my (@row) = $sth->fetchrow_array();
	print "VALS from database:\n" . (join ("\n", grep defined, @row)) . "\n";
    } else {
	print "error running statement";
    }
}

# test_dbh();
# exit 0;

sub zip_file_valid { #can a zip file be parsed?
    my ($zip_file) = shift;
    system ("unzip -p $zip_file >/dev/null 2>&1");
    return ! $?;
}


sub load_zip_using_gtfsdb {
    my ($zip_file) = shift;
    my ($schema) = shift;
    my (@commands) = ("gtfsdb-load", "--database_url",
                      "postgresql://$db_user:$db_pass\@localhost/$db_name",
		      "--schema", $schema,
		      "--is_geospatial",
	              $zip_file);
    print "VAR gtfsdb commands: " . (join " ", @commands);
    return ! system (@commands);
}

# list_append ([1, 2, 3], 4, 5) => (1, 2, 3, 4, 5)
sub list_append {
    my @list = shift;
    my @args = @_;
    my @copy = ();

    for $i (@list) { push @copy, $i; }
    for $i (@args) { push @copy, $i; }

    return @copy;
}

sub run_psql_command {
    my ($sql) = shift;    

    # return ! system (list_append( @psql_cmds, "-c", $sql));
    my ($sth) = $dbh->prepare($sql);
    my ($rv) = $sth->execute;

    return $rv;
}

sub create_schema {
    my ($schema) = shift;
    my ($schema_command) = 'CREATE SCHEMA IF NOT EXISTS "' . $schema . '";';
    
    #print "VAR. schema_command: $schema_command\n";
    # print "\@psql_cmds: " . (join " ", @psql_cmds) . "\n";
    # print 'append(@psql_cmds, ...) ' . (join " ", (list_append @psql_cmds, "-c", "$schema_command")) . "\n";
    # print '@commands ' . (join " ", @commands) . "\n";

    return run_psql_command ( $schema_command);
}

## FIXME FIXME: I'm not sure the following is true. current_datafile
## is an unique key, it is dataexchange_id which is not unique. What
## we actually need to do is throw out the _NNNNN_NNN when determining
## the dataexchange_id.
##
## ?FIXME?: this does not account for the possibility that multiple
## copies of data for the same agency may be loaded at once.
## 
## api-gtfs uses the "date_last_updated" column to disambiguate GTFS
## archvies which represent the same data which were fetched at
## different times.
##
## What we should do is read the zip filename and decode the
## "last_updated" date, then use that as an additional primary key
## when updating the database.
## 
## Another option is to NEVER change existing data, only creating new
## sails_agency entries if one does not exist yet. That way we avoid
## stomping over someone else's data.

sub update_sails_agency_for_datafile { 

    ## HACK HACK. Check if we're supposed to update this table.
    ##
    ## TODO: Add command line parsing for --update-sails-agency
    ## option, and our caller should decide whether or not to call us.
    unless ($create_api_gtfs_tables) { return; }

    my ($datafile) = shift;
    my ($schema) = $datafile;
    my ($dataexchange_id) = $datafile; $dataexchange_id =~ s/_\n+_\n+$//g;

    my ($sth, $rv) ;
    
    if (sails_agency_exists_for_datafile( $datafile )) {
	## it already exists, issue update.
	$sth = $dbh->prepare('UPDATE sails_agency SET "updatedAt" = NOW() WHERE current_datafile=?;');
	$rv = $sth->execute($datafile); 
   } else {
	## doesn't exist yet, create it.
	$sth = $dbh->prepare('INSERT INTO sails_agency ("createdAt", "updatedAt", current_datafile, dataexchange_id, name) ' 
			     . 'VALUES (NOW(), NOW(), ?, ?, ?)');
	$rv = $sth->execute($datafile, $dataexchange_id, $datafile);
    }
    
    # return run_psql_command( $sql );
}

sub sails_agency_exists_for_datafile { 

    ## HACK HACK. Check if we're supposed to update this table.
    ##
    ## TODO: Add command line parsing for --update-sails-agency
    ## option, and our caller should decide whether or not to call us.
    unless ($create_api_gtfs_tables) { return; }


    my ($datafile) = shift;
    my ($schema) = $datafile;
    my ($dataexchange_id) = $datafile; $dataexchange_id =~ s/_\n+_\n+$//g;
    
    my ($sth) = $dbh->prepare("SELECT * from sails_agency where current_datafile=?;");
    my ($rv) = $sth->execute($datafile);

    if ($rv > 0) {
	my (@row) = $sth->fetchrow_array();
	print "FOUND agency entry for $datafile: " . (join (" ", grep defined, @row)) . "\n";
    } else {
	print "DID NOT FIND agency entry for $datafile\n";
    }

    return ($rv and ($rv > 0));
}

# print "data dir is $data_dir\n";
# system ("ls -l $data_dir");
sub main () {
    my ($d, $z, $data_dir_handle, $dh);

    opendir ($data_dir_handle,  $data_dir) or die "cannot open $data_dir directory";
    
  DATA_SUBDIR: for $d (readdir $data_dir_handle) {
      next DATA_SUBDIR if $d =~ /^\./; #skip hidden files.
      $d = "$data_dir/$d"; # add prefix
      next DATA_SUBDIR unless -d $d; #we only care about directories.
      
      print "reading subdirectory $d\n";
      opendir (my $dh,  $d) or die "cannot open $d directory";
      
    ZIP_FILE: for $z (readdir $dh) {
	next ZIP_FILE if $z =~ /^\./; #skip hidden files.
	
	my ($schema) = $z; $schema =~ s/\.zip$//i;
	my ($zip_file) = "$d/$z";
	my ($valid_zip_file) = zip_file_valid($zip_file);
	
	print "VAR. z: $z schema: $schema\n";
	#print "VAR. zip_file: $zip_file\n";
	#print "file is " . ( zip_file_valid($zip_file) ? "OK!! " :  "NOT SO HOT!! ") . "\n";
	
	unless (zip_file_valid ($zip_file)) {
	    print "zip file cannot be parsed: $zip_file, skipping!\n";
	    next ZIP_FILE;
	}
	
	print "Creating schema '$schema'.\n";
	create_schema($schema) or print "problem creating schema $schema\n";

	print "updating sails_agency table.\n";
	update_sails_agency_for_datafile ($schema);

	print "Loading zip file into schema '$schema' using gtfsdb-load.\n";
	load_zip_using_gtfsdb( $zip_file, $schema) or print "problem loading gtfs data for $schema";
    }
  }
}


sub test_lookup {
    print "looking up foo, it was "
	. ((sails_agency_exists_for_datafile ("foo"))
	   ? "FOUND!" : "NOT FOUND") . "\n";
    print "looking up cape-ann-transportation-authority_20121101_0348, it was "
	. ((sails_agency_exists_for_datafile ("cape-ann-transportation-authority_20121101_0348"))
	   ? "FOUND!" : "NOT FOUND") . "\n";
}

main();

#system($psql . ' -c \\\\dt');
#system($psql . ' -c \\\\dn');

