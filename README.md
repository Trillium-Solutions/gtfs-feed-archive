# gtfs-feed-archive

A Clojure library to create an archive of GTFS (general transit feed) data.

## Building

lein uberjar

## Usage

Command line operation & web interface.

Create a GTFS feed archive. http://github.com/ed-g/gtfs-feed-archive

 Switches                   Default           Desc                                                               
 --------                   -------           ----                                                               
 -o, --output-directory                       Directory to place output zip files into.                          
 -i, --input-csv                              CSV feed list file.                                                
 -c, --cache-directory      /tmp/gtfs-cache/  Cache directory for GTFS feed downloads.                           
 -u, --no-update, --update  false             Fetch updated feeds from the Internet and store them in the cache. 
 -s, --since-date                             Create an archive of feeds modified after date, e.g. 2013-08-23.   
 -a, --no-all, --all        false             Make an archive of all feeds.                                      

## License

Copyright © 2013,2014 Ed Groth

http://github.com/ed-g

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
more details.

You should have received a copy of the GNU General Public License along with
this program, in gpl.txt.  If not, see <http://www.gnu.org/licenses/>.
