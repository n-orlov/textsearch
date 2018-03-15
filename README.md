### BUILDING AND RUNNING THE APP
* Run `mvn clean install` to build
* Run `java -jar <built jar name>` to run. Add `-Dspring.profiles.active=debug|trace` for debug or trace log output.
You may also add `-Xms` and `-Xmx` options to control the heap size.
* Application runs at `http://localhost:8080/` by default.