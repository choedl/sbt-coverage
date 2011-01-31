sbt-coverage code coverage processor for sbt
============================================

Version: 0.1

sbt-coverage produces code coverage reports of projects in sbt using the
[undercover](http://code.google.com/p/undercover/) code coverage tool.

Prerequisites
-------------

The current version of this sbt processor relies on test callback classloader
information provided in version `0.7.5.RC0` of sbt. From all the testing I
have carried out sbt `0.7.5.RC0` is as stable as the current stable release
`0.7.4` so you should be able to upgrade the version for each sbt project by
entering:

    > set sbt.version 0.7.5.RC0
    ...
    > reload
    ...

Also note that the current version of this sbt processor is only compiled for
Scala 2.7.7. If you have set the `default.scala.version` in sbt to `2.8.1` or
any other version, then the sbt processor will fail to load as it will fail to
find a build of the current version. However, current sbt runs in version
2.7.7 by default even when building code for 2.8.1 so this is unlikely to be a
problem.

Installing
----------

To install the sbt processor which will then work in any of your sbt projects
just run sbt and enter:

    > *undercoverRepo at http://undercover.googlecode.com/svn/maven/repository/
    ...
    > *coverage is com.proinnovate sbt-coverage 0.1
    ...
    
The first line defines the repository for getting the undercover
dependency.  The second line installs the processor from the Maven repository
at `scala-tools.org` and calls it `coverage`.

If you are making changes to the code and wish to reload the processor you
need to remove the existing one first with:

    > *remove coverage
    ...
    
You may also need to exit sbt and start it again for the new coverage tool
to work properly.

Using the processor
-------------------

First install the processor (see above), then open up an existing sbt Scala /
Java project and (in the sbt command line) enter the command:

    > coverage
    ...

This will compile your code, instrument the classes, run all your tests with
the instrumented main classes and then produce a test report which should be
automatically opened in your default web browser.

The resulting output should look a bit like this:

![Example screen shot](http://farm3.static.flickr.com/2558/4109571846_5bc8da4cc3.jpg)

### Additional commands

In addition to the standard `coverage` command you can also enter:

 * `coverage compile` - to compile the code and instrument the classes but
   nothing else.
 * `coverage test` - to compile, instrument and test but not produce a
   report.
 * `coverage report` - a long form of just entering `coverage`.  This
   does everything.  It optionally takes a space-separated list of
   output formats, which can contain `html`, `coberturaxml`, or `emmaxml`.
 * `coverage clean` - clean the code coverage output files from your target
   directory.  This is probably a good thing to do whenever you are producing
   a new report as there is currently no code which checks to make sure that
   old files are removed.

License
-------

This software is Copyright © 2011, Stuart Roebuck and licensed under a
standard MIT license (more specifically referred to as the Expat license). See
the `LICENSE.md` file for details.

