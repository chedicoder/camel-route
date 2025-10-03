// hello.groovy
from("timer:foo?period={{myPeriod}}")
    .routeId("timer")
    .log("hello Chedi from Camel, I'm using Groovy DSL")

// Route volontairement invalide pour tester le supervising route controller
from("netty:tcp:unknownhost")
    .routeId("netty")
    .to("log:dummy")
