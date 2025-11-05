// camel-k: build-property=quarkus.datasource.camel.db-kind=oracle
// camel-k: dependency=camel:jdbc
// camel-k: dependency=mvn:io.quarkus:quarkus-jdbc-oracle:2.10.0.Final
// camel-k: dependency=mvn:com.fasterxml.jackson.core:jackson-databind:2.12.3
// camel-k: dependency=mvn:org.codehaus.groovy:groovy-json:3.0.9
// camel-k: dependency=camel:http
// camel-k: language=groovy

import org.apache.camel.Exchange
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import org.apache.camel.component.http.HttpComponent
import org.apache.camel.component.http.HttpClientConfigurer
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder

// Configuration du contexte SSL pour faire confiance à tous les certificats (solution temporaire)
def sslContext = SSLContext.getInstance("TLS")
def trustAllCerts = [
    new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { null }
        void checkClientTrusted(X509Certificate[] chain, String authType) {}
        void checkServerTrusted(X509Certificate[] chain, String authType) {}
    }
] as TrustManager[]
sslContext.init(null, trustAllCerts, new java.security.SecureRandom())

def sslSocketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)
def connectionManager = PoolingHttpClientConnectionManagerBuilder.create().setSSLSocketFactory(sslSocketFactory).build()
def insecureConfigurer = new HttpClientConfigurer() {
    @Override
    void configureHttpClient(HttpClientBuilder clientBuilder) {
        clientBuilder.setConnectionManager(connectionManager)
    }
}
def httpComponent = context.getComponent("https", HttpComponent.class)
httpComponent.setHttpClientConfigurer(insecureConfigurer)

def log = LoggerFactory.getLogger('TaskProcessing')

// Chargement des propriétés externalisées
String authToken = '{{app.pc.auth.token}}'
String camundaApiUrl = "{{app.camunda.api-url}}"
String fetchTasksQuery = "{{app.sql.fetch-tasks}}"
String updateTaskStatusQuery = "{{app.sql.update-task-status}}"

// Gestion des exceptions globales
onException(Exception.class)
    .process { exchange ->
        def exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)
        def errorMessage = "ERROR: Exception dans l'exécution de la route : ${exception?.message}"
        exchange.getIn().setHeader("errorMessage", errorMessage)
        log.error(errorMessage)
    }
    .handled(true)

// Route principale pour récupérer les tâches et les traiter
from("timer:fetchTasks?period=5000") // Exécution périodique toutes les 5 secondes
    .routeId("fetch-tasks")
    .log("INFO: Démarrage du processus de récupération des tâches...")
    .setBody().constant(fetchTasksQuery)
    .to("jdbc:camel") // Exécution de la requête SQL pour récupérer les tâches
    .process { exchange ->
        def taskList = exchange.getIn().getBody(List)
        log.info("DEBUG: Tâches récupérées : " + taskList)
        def currentTimestamp = LocalDateTime.now().withSecond(0).withNano(0)

        // Vérification si des tâches sont disponibles
        if (taskList?.isEmpty()) {
            log.info("INFO: Aucune tâche avec STATUS = VALIDATION trouvée.")
            return
        }

        // Filtrage des tâches à traiter
        taskList.each { task ->
            try {
                def scheduleDate = task.SCHEDULE_DATE instanceof oracle.sql.TIMESTAMP
                    ? task.SCHEDULE_DATE.timestampValue().toLocalDateTime().withSecond(0).withNano(0)
                    : task.SCHEDULE_DATE

                if (scheduleDate?.toLocalDate() <= currentTimestamp.toLocalDate() &&
                    scheduleDate.getHour() <= currentTimestamp.getHour() &&
                    scheduleDate.getMinute() <= currentTimestamp.getMinute()) {

                    // Envoi de la tâche vers le traitement
                    exchange.getContext().createProducerTemplate().send("direct:toPC") { subExchange ->
                        subExchange.getIn().setHeader("task", task)
                    }
                } else {
                    log.info("DEBUG: Tâche ignorée ID: ${task.ID} - Planifiée à : ${scheduleDate} (non conforme)")
                    }
            } catch (Exception e) {
                log.error("ERROR: Erreur lors du traitement de la tâche ID: ${task.ID} - ${e.message}")
            }
        }
    }

// Route pour traiter une tâche spécifique et démarrer un processus Camunda
from("direct:toPC")
    .routeId("process-task")
    .process { exchange ->
        def objectMapper = new ObjectMapper()
        def tasked = exchange.getIn().getHeader("task")

        // Validation des données extraites
        if (!tasked?.ID || !tasked?.STATUS) {
            throw new IllegalArgumentException("ERROR: Données de tâche invalides ou manquantes : ${tasked}")
        }

        // Traitement des champs pour les rendre exploitables
        def handleField = { field ->
            try {
                if (field == null) return null
                if (field instanceof oracle.sql.TIMESTAMP) {
                    return field.timestampValue().toLocalDateTime().toString()
                } else if (field instanceof oracle.sql.CLOB) {
                    return field.getSubString(1, (int) field.length())
                } else {
                    return field
                }
            } catch (Exception e) {
                log.error("ERROR: Erreur lors du traitement du champ : ${e.message}")
                return "Error handling field: ${e.message}"
            }
        }

        // Préparation des données de la tâche
        def serializedTask = tasked.collectEntries { key, value -> [key, handleField(value)] }

        def urlObject = [url: serializedTask.URL]

        def taskIdValue = serializedTask.TASK_ID_ALIAS

        def requestBody = [
    variables: [
        taskId: [
            value: taskIdValue,
            type : 'Long'
        ]
    ],
    businessKey : taskIdValue
]
        exchange.getIn().setHeader('taskId', taskIdValue)
        exchange.getIn().setHeader("Authorization", "Bearer " + exchange.getContext().resolvePropertyPlaceholders(authToken))
        exchange.getIn().setBody(objectMapper.writeValueAsString(requestBody))
    }
    .setHeader(Exchange.HTTP_METHOD, constant("POST"))
    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
    .toD(camundaApiUrl) // Envoi de la requête POST vers l'API Camunda
    .process { exchange ->
        try {
            def response = exchange.getMessage().getBody(String) // Get response body as a string
            if (response) {
                log.info("INFO: Processus Camunda démarré avec succès. Réponse : ${response}")
            } else {
                log.warn("WARN: La réponse est vide ou nulle après l'envoi de la requête POST.")
            }

            // Mise à jour du statut de la tâche dans la base de données
            def taskId = exchange.getIn().getHeader('taskId')
            def updateQuery = exchange.getContext().resolvePropertyPlaceholders(updateTaskStatusQuery).replace(":taskId", taskId.toString())
            exchange.getContext().createProducerTemplate().send("direct:updateTaskStatus") { subExchange ->
                subExchange.getIn().setBody(updateQuery)
                subExchange.getIn().setHeader('taskId', taskId)
            }

        } catch (Exception e) {
            log.error("ERROR: Erreur lors de la mise à jour du statut de la tâche : ${e.message}")
        }
    }

// Route pour exécuter la mise à jour du statut
from("direct:updateTaskStatus")
    .routeId("update-task-status")
    .to("jdbc:camel") // Exécution de la requête SQL pour mettre à jour la tâche
    .process { exchange ->
        def body = exchange.getIn().getBody() // Access the body explicitly
        def taskId = exchange.getIn().getHeader('taskId')

        if (body) {
            exchange.getMessage().setHeader("logMessage", "INFO: Statut mis à jour à STARTED pour la tâche ID : ${taskId}. Détails : ${body}")
        } else {
            exchange.getMessage().setHeader("logMessage", "WARN: Mise à jour du statut échouée ou aucune tâche trouvée pour ID : ${taskId}")
        }
    }
    .log('${header.logMessage}')
