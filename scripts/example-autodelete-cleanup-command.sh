#!/bin/bash
# You can customize this command by: 
#   * Changing the directory arguments to find, to point at your GTFS-archive web root.
#   * Changing the -newerct option to give your expiration time.
# This file is in the public domain.
# Ed 2017-01-24
find /var/www/html/archive*/ -name '*.autodelete.zip' \! -newerct '30 minutes ago' -execdir 'echo' '{}' 'shall be deleted' ';' -execdir 'rm' '{}' ';'

