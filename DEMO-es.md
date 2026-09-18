# Data Grid PoC: Guion de Demo

> Audiencia: Equipo tecnico (middleware, infraestructura)
>
> Objetivo: Demostrar que Data Grid reemplaza a Redis como servicio de cache centralizado y soporta multiples modelos de consumo con multi-tenancy, HA y GitOps.

---

## Antes de Empezar

**Herramientas necesarias:** `oc`, `curl`, `jq`

> [!CAUTION]
> Ejecute todas las verificaciones previas antes de comenzar la demo. Un cluster caido o un pod ausente bloqueara los pasos siguientes.

**El entorno debe estar listo:**

```bash
# Verificar que los clusters estan sanos
oc get infinispan -n rhdg-poc
# Esperado: healthcare (2 replicas), platform (3 replicas)

# Verificar que todas las apps estan ejecutandose
oc get pods -n rhdg-poc --no-headers | grep -v build | grep Running | wc -l
# Esperado: 15 (2 healthcare + 3 platform + 2 config-listeners + 1 operator + 6 apps + 1 redis)

# Verificar que ArgoCD esta sincronizado
oc get application.argoproj.io datagrid-poc -n openshift-gitops \
  -o jsonpath='Sync: {.status.sync.status}, Health: {.status.health.status}'
# Esperado: Sync: Synced, Health: Healthy
```

**Configurar las variables de ruta (se usan durante toda la demo):**

```bash
export ROUTE_REDIS=$(oc get route redis-client-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_SPRING=$(oc get route spring-cache-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_QUARKUS=$(oc get route quarkus-cache-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_SESSION=$(oc get route spring-session-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_REST=$(oc get route rest-loader-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_EAP=$(oc get route eap-session-app -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_HC=$(oc get route healthcare-external -n rhdg-poc -o jsonpath='{.spec.host}')
export ROUTE_PF=$(oc get route platform-external -n rhdg-poc -o jsonpath='{.spec.host}')
```

> [!WARNING]
> La aplicacion `redis-client-app` **debe** empezar apuntando a Redis (no a Data Grid). El cambio en vivo del Acto 2 demuestra la transparencia RESP. Si ya apunta a Data Grid, resetearla primero.

**Asegurarse de que redis-client-app apunta a Redis (resetear si es necesario):**

```bash
curl -sk "https://${ROUTE_REDIS}/api/info" | jq .
# Debe mostrar: "backend_host": "redis", "backend_port": 6379
# Si no, parchear de vuelta a Redis:
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-redis.yaml
oc rollout status deployment/redis-client-app -n rhdg-poc
```

---

## Acto 1: El Problema

La organizacion necesita un servicio de cache centralizado que multiples proyectos y aplicaciones puedan consumir. Hoy, cada equipo implementa su propio cache (tipicamente Redis), generando silos, duplicacion y sobrecarga operativa. El objetivo es un unico servicio gestionado de "Cache as a Service" que soporte multiples modelos de consumo y sea multi-tenant.

**Topologia del entorno:**

```bash
oc get infinispan -n rhdg-poc
```

Dos clusters independientes de Data Grid ejecutandose en OpenShift: `healthcare` (2 nodos, ligero, para datos efimeros) y `platform` (3 nodos, mas robusto, con almacenamiento persistente). Cada cluster tiene sus propias credenciales, sus propios caches y sus propias aplicaciones. Este es el modelo de multi-tenancy: un Operator, multiples clusters aislados.

```bash
oc get caches -n rhdg-poc
```

Cinco caches definidos por las aplicaciones, cada uno con un proposito especifico: correlacion de eventos, datos de referencia, sesiones de usuario, sesiones EAP y configuracion de aplicaciones. El modo de cache (distribuido o replicado), el modo de replicacion (SYNC o ASYNC), el TTL y la persistencia se configuran por cache, no por cluster.

---

## Acto 2: Transparencia del Protocolo RESP

Las aplicaciones que ya usan Redis a traves de Jedis, Lettuce o redis-cli pueden comunicarse con Data Grid sin cambiar una sola linea de codigo. Este acto lo demuestra.

### 2.1 Mostrar la app funcionando contra Redis

```bash
# Informacion de conexion (actualmente apuntando a Redis)
curl -sk "https://${ROUTE_REDIS}/api/info" | jq .
```

La salida muestra host `redis`, puerto `6379`, protocolo Redis estandar.

```bash
# Escribir y leer a traves de la API de la aplicacion
curl -sk -X PUT "https://${ROUTE_REDIS}/api/strings/patient-001" \
  -H "Content-Type: text/plain" -d "John Doe - Cardiology"
curl -sk "https://${ROUTE_REDIS}/api/strings/patient-001" | jq .
```

### 2.2 Mostrar redis-cli funcionando contra Redis

```bash
POD=$(oc get pod -l app=redis-client-app -n rhdg-poc -o jsonpath='{.items[0].metadata.name}')

# Comandos Redis estandar via CLI
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning SET appointment:001 "Confirmed - Dr. Brown"
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning GET appointment:001

# Contador (incremento atomico)
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning INCR visit-counter
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning INCR visit-counter
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning GET visit-counter

# Hash (datos estructurados)
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning HSET patient:001 name "John Doe" department "Cardiology" status "Active"
oc exec $POD -n rhdg-poc -- redis-cli -h redis -p 6379 -a 'r3d1s-p0c-2026' --no-auth-warning HGETALL patient:001
```

Operaciones Redis estandar: strings, contadores, hashes.

### 2.3 Cambio en vivo a Data Grid

> [!IMPORTANT]
> Lo UNICO que cambia es el endpoint de conexion: host, puerto y credenciales. El codigo de la aplicacion, el cliente Jedis y los comandos redis-cli permanecen identicos.

```bash
# Parchear el deployment (este es el UNICO cambio)
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-datagrid.yaml
oc rollout status deployment/redis-client-app -n rhdg-poc
```

```bash
# Informacion de conexion (ahora apuntando a Data Grid)
curl -sk "https://${ROUTE_REDIS}/api/info" | jq .
```

La salida ahora muestra host `healthcare`, puerto `11222`, usuario `app-healthcare`, con TLS habilitado.

### 2.4 Mismos comandos, backend Data Grid

```bash
# Mismas llamadas API, comportamiento identico
curl -sk -X PUT "https://${ROUTE_REDIS}/api/strings/patient-001" \
  -H "Content-Type: text/plain" -d "John Doe - Cardiology"
curl -sk "https://${ROUTE_REDIS}/api/strings/patient-001" | jq .
```

```bash
POD=$(oc get pod -l app=redis-client-app -n rhdg-poc -o jsonpath='{.items[0].metadata.name}')

# Mismos comandos redis-cli, ahora contra Data Grid
oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure SET appointment:001 "Confirmed - Dr. Brown"
oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure GET appointment:001

oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure INCR visit-counter
oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure INCR visit-counter
oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure GET visit-counter

oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure HSET patient:001 name "John Doe" department "Cardiology" status "Active"
oc exec $POD -n rhdg-poc -- redis-cli -h healthcare -p 11222 --user app-healthcare -a 'h34lthc4r3!' --no-auth-warning --tls --insecure HGETALL patient:001
```

Resultados identicos. El mismo binario `redis-cli`, los mismos comandos Redis, el mismo codigo del cliente Jedis. La diferencia es quien esta detras del protocolo: Data Grid en lugar de Redis.

### 2.5 Volver a Redis (opcional, demuestra bidireccionalidad)

```bash
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-redis.yaml
oc rollout status deployment/redis-client-app -n rhdg-poc
curl -sk "https://${ROUTE_REDIS}/api/info" | jq .
# De vuelta en Redis, la transparencia funciona en ambas direcciones
```

Cualquier aplicacion que use Redis hoy puede cambiar a Data Grid modificando unicamente el endpoint de conexion. Sin cambios de codigo, sin cambios de librerias, sin recompilacion.

### 2.6 Interfaz Web en Tiempo Real y Pub/Sub Cross-App

Abra la UI de `redis-client-app` en un navegador (asegurese de que la app apunta a Data Grid tras el paso 2.3):

```
https://redis-client-app-rhdg-poc.apps.ocp4.masales.cloud
```

La interfaz muestra:

- **Metadatos de conexion**: nombre del cluster, nombre del cache, modo, host, puerto, estado de conexion
- **Entradas del cache**: todas las keys con valor, tipo y TTL (TTL por defecto a nivel de cache: 300s)
- **Log de eventos en vivo**: eventos Pub/Sub via SSE (canal `cache-events`)

Utilice el formulario integrado para SET y DEL entradas. Los cambios aparecen en la tabla y el log de eventos instantaneamente.

**Visibilidad cross-app:** En una terminal separada, ejecute el `external-cache-client` localmente. Se conecta a Data Grid via la Route RESP externa y publica en el mismo canal Pub/Sub.

```bash
cd apps/external-cache-client
mvn quarkus:dev

# En otra terminal
curl -X PUT http://localhost:8080/api/cache/external-entry -H "Content-Type: text/plain" -d "from-external-client"
```

La entrada aparece en la UI web de `redis-client-app` en tiempo real. La consola del `external-cache-client` tambien registra los eventos de otras aplicaciones. Esto demuestra el uso compartido de cache entre multiples aplicaciones sobre el protocolo RESP con notificaciones en tiempo real via Pub/Sub.

---

## Acto 3: Multiples Modelos de Consumo

La compatibilidad RESP cubre aplicaciones que ya usan Redis. Pero Data Grid ofrece mucho mas: clientes nativos para Java (Hot Rod), REST HTTP estandar para cualquier lenguaje, e integraciones con frameworks como Spring y Quarkus. Cinco formas diferentes de consumir el mismo servicio de cache:

### 3.1 Spring Cache: @Cacheable (Java/Spring Boot)

```bash
# Primera llamada: cache miss (lento, ~500ms de busqueda simulada)
curl -sk "https://${ROUTE_SPRING}/api/specialties/CAR" | jq .

# Segunda llamada: cache hit (instantaneo, desde Data Grid)
curl -sk "https://${ROUTE_SPRING}/api/specialties/CAR" | jq .

# Estadisticas del cache: solo 1 busqueda a pesar de 2 llamadas
curl -sk "https://${ROUTE_SPRING}/api/specialties/stats" | jq .
```

Una anotacion `@Cacheable` en el metodo. Spring gestiona la busqueda en cache, el miss, el almacenamiento y el hit. Data Grid es el almacen de respaldo via protocolo Hot Rod.

### 3.2 Quarkus: mismo cache, diferente runtime

```bash
# Mismos datos, mismo cache (app-config), diferente runtime
curl -sk "https://${ROUTE_QUARKUS}/api/specialties" | jq .

curl -sk "https://${ROUTE_QUARKUS}/api/specialties/CAR" | jq .
curl -sk "https://${ROUTE_QUARKUS}/api/specialties/stats" | jq .
```

Esta aplicacion Quarkus se conecta exactamente al mismo cache `app-config` en el cluster `healthcare`. Dos runtimes (Spring Boot, Quarkus), mismo cache, mismos datos. El cache es infraestructura compartida, no esta ligado a un framework.

### 3.3 REST API: agnositco del lenguaje (C#/.NET)

```bash
# Cargar datos de referencia (5 hospitales) via REST
curl -sk -X POST "https://${ROUTE_REST}/api/cache/load" | jq .

# Leer una entrada especifica
curl -sk "https://${ROUTE_REST}/api/cache/HOS-001" | jq .

# Listar todas las claves
curl -sk "https://${ROUTE_REST}/api/cache/keys"
```

Una aplicacion ASP.NET Core escrita en C#, sin ninguna libreria cliente de Java o Infinispan. Solo llamadas HTTP planas a la API REST de Data Grid. Cualquier lenguaje que pueda hacer peticiones HTTP puede consumir Data Grid: Python, Go, Node.js, PHP.

### 3.4 Spring Session: sesiones HTTP externalizadas

```bash
# Crear una sesion
curl -sk -c /tmp/cookies.txt -X PUT "https://${ROUTE_SESSION}/api/session/attributes/user" \
  -H "Content-Type: text/plain" -d 'user.demo' | jq .

# Leerla de vuelta (misma cookie de sesion)
curl -sk -b /tmp/cookies.txt "https://${ROUTE_SESSION}/api/session" | jq .

# Verificar que la sesion esta almacenada en Data Grid (no en el pod)
curl -sk -u ops-admin:'r3dh4t2026!' "https://${ROUTE_PF}/rest/v2/caches/user-sessions?action=size"
```

La sesion HTTP vive en Data Grid, no en el pod de la aplicacion. Si el pod se reinicia, la sesion sobrevive. Esto permite el escalado horizontal: cualquier replica puede atender a cualquier usuario porque la sesion es externa.

### 3.5 JBoss EAP 8: externalizacion de sesiones

```bash
# Crear una sesion via EAP
curl -sk -c /tmp/eap-cookies.txt -X PUT "https://${ROUTE_EAP}/eap-sessions/api/session/attributes/doctor" \
  -H "Content-Type: text/plain" -d 'Dr. Smith' | jq .

# Leerla de vuelta
curl -sk -b /tmp/eap-cookies.txt "https://${ROUTE_EAP}/eap-sessions/api/session" | jq .

# Verificar en Data Grid
curl -sk -u ops-admin:'r3dh4t2026!' "https://${ROUTE_PF}/rest/v2/caches/eap-sessions.war?action=size"
```

JBoss EAP externaliza sesiones a Data Grid de forma nativa a traves de las capas Galleon `cloud-server` y `web-clustering`. Sin codigo personalizado, solo configuracion.

> [!NOTE]
> Cinco modelos de consumo: RESP (compatibilidad Redis), Spring Cache, Quarkus, REST API y sesiones EAP. Tres runtimes: Spring Boot, Quarkus, ASP.NET Core. Un servicio de cache.

---

## Acto 4: Correlacion de Eventos

Almacenamiento temporal de IDs de correlacion para la entrega asincrona de mensajes. Un sistema envia un mensaje y necesita rastrear si el destinatario lo confirmo. Data Grid almacena la correlacion con un TTL de 5 minutos. Este cache usa replicacion ASYNC para maximo rendimiento de escritura.

```bash
# 1. Enviar un mensaje (crea una entrada de correlacion con estado PENDING)
SEND=$(curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=user-001&payload=appointment-confirmed")
echo $SEND | jq .
CID=$(echo $SEND | jq -r '.correlationId')
echo "Correlation ID: $CID"
```

El estado es PENDING. El ID de correlacion es un UUID. La entrada expira automaticamente en 5 minutos.

```bash
# 2. Verificar el estado actual
curl -sk "https://${ROUTE_QUARKUS}/api/events/${CID}" | jq .

# 3. El sistema externo confirma la entrega
curl -sk -X PUT "https://${ROUTE_QUARKUS}/api/events/${CID}/ack" | jq .

# 4. Verificar el estado final (ahora DELIVERED)
curl -sk "https://${ROUTE_QUARKUS}/api/events/${CID}" | jq .
```

PENDING a DELIVERED. El ciclo de vida completo de una correlacion de mensajes: crear, verificar, confirmar. Los datos expiran automaticamente despues de 5 minutos, sin necesidad de limpieza manual.

```bash
# Enviar unos cuantos mas para mostrar la lista
curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=user-002&payload=results-available" > /dev/null
curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=user-003&payload=prescription-renewed" > /dev/null

# Listar todas las correlaciones activas
curl -sk "https://${ROUTE_QUARKUS}/api/events" | jq .
```

Este cache usa replicacion ASYNC: las escrituras retornan tan pronto como el propietario primario almacena la entrada. La replicacion al segundo propietario ocurre en segundo plano. Para datos de eventos temporales y reintentables, ASYNC ofrece maximo rendimiento con minima latencia.

---

## Acto 5: Multi-Tenancy y Seguridad

Cada proyecto o equipo obtiene su propio cluster de Data Grid aislado. Este acto demuestra como se ve el aislamiento en la practica.

```bash
# Dos clusters independientes, diferentes tamanos, diferentes propositos
oc get infinispan -n rhdg-poc
oc get pods -l app=infinispan-pod -n rhdg-poc
```

```bash
# Cada cluster tiene sus propias credenciales
oc get secret healthcare-credentials -n rhdg-poc -o jsonpath='{.data.identities\.yaml}' | base64 -d
echo "---"
oc get secret platform-credentials -n rhdg-poc -o jsonpath='{.data.identities\.yaml}' | base64 -d
```

Diferentes usuarios, diferentes contrasenas, diferentes roles. `app-healthcare` solo puede acceder al cluster healthcare. `app-platform` solo puede acceder al cluster platform.

```bash
# Demostrar el aislamiento: credenciales de healthcare rechazadas por platform
curl -sk -u app-healthcare:'h34lthc4r3!' "https://${ROUTE_PF}/rest/v2/caches" -w "\nHTTP %{http_code}\n"
# Esperado: 401 Unauthorized
```

Aislamiento de credenciales por diseno. Comprometer las credenciales de un cluster no expone al otro. Cada cluster es un StatefulSet separado con sus propios pods, configuracion e identidad de red.

```bash
# Mostrar que cada cluster tiene diferentes caches
echo "=== Healthcare caches ==="
curl -sk -u ops-admin:'r3dh4t2026!' "https://${ROUTE_HC}/rest/v2/caches" | jq .
echo "=== Platform caches ==="
curl -sk -u ops-admin:'r3dh4t2026!' "https://${ROUTE_PF}/rest/v2/caches" | jq .
```

El usuario `ops-admin` tiene acceso admin a ambos clusters (para operaciones). Los usuarios de aplicacion solo tienen acceso de lectura/escritura a su propio cluster. Modelo RBAC: rol `application` para datos, rol `admin` para gestion.

---

## Acto 6: Resiliencia

### 6.1 Alta Disponibilidad: eliminar un pod sin perder datos

Data Grid distribuye los datos entre multiples nodos. Cada entrada se almacena en 2 propietarios (`owners: 2`). Si un nodo cae, el otro propietario conserva los datos.

```bash
# 1. Verificar el estado del cluster
oc get pods -l clusterName=healthcare -n rhdg-poc

# 2. Insertar 3 eventos
curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=ha-test-001&payload=before-failover" > /dev/null
curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=ha-test-002&payload=before-failover" > /dev/null
curl -sk -X POST "https://${ROUTE_QUARKUS}/api/events/send?recipient=ha-test-003&payload=before-failover" > /dev/null

# 3. Contar entradas
echo "Before failover:"
curl -sk "https://${ROUTE_QUARKUS}/api/events" | jq 'length'
```

```bash
# 4. Eliminar un pod
oc delete pod healthcare-0 -n rhdg-poc
echo "Pod eliminated. Waiting for recovery..."

# 5. Esperar la recuperacion
sleep 5
oc get pods -l clusterName=healthcare -n rhdg-poc
```

El StatefulSet recrea el pod automaticamente. Durante la recuperacion, el pod superviviente (`healthcare-1`) continua atendiendo peticiones.

```bash
# 6. Verificar que los datos sobrevivieron (cero perdida)
echo "After failover:"
curl -sk "https://${ROUTE_QUARKUS}/api/events" | jq 'length'

# 7. Esperar la recuperacion completa y verificar que el cluster esta WellFormed de nuevo
oc wait --for=condition=WellFormed infinispan/healthcare -n rhdg-poc --timeout=120s
oc get infinispan healthcare -n rhdg-poc
```

Mismo numero de eventos. Cero perdida de datos. El cluster se reparo automaticamente.

### 6.2 Auto-reparacion GitOps: ArgoCD revierte cambios manuales

ArgoCD monitoriza las diferencias entre el estado del cluster y lo definido en Git. Los cambios manuales que contradigan Git se revierten automaticamente.

```bash
# 1. Mostrar el estado actual de ArgoCD
oc get application.argoproj.io datagrid-poc -n openshift-gitops \
  -o jsonpath='Sync: {.status.sync.status}, Health: {.status.health.status}'
echo ""

# 2. Cambiar manualmente un deployment (simular un cambio no autorizado)
oc scale deployment spring-cache-app -n rhdg-poc --replicas=3
echo "Manually scaled spring-cache-app to 3 replicas"

# 3. Esperar a que ArgoCD detecte y revierta
echo "Waiting for ArgoCD to detect drift and revert..."
sleep 20

# 4. Comprobar: ArgoCD deberia haberlo escalado de vuelta a 1
oc get deployment spring-cache-app -n rhdg-poc -o jsonpath='Replicas: {.spec.replicas}'
echo ""
```

> [!NOTE]
> ArgoCD tipicamente detecta la desviacion en 10-30 segundos. La configuracion `selfHeal: true` asegura que el cluster siempre converja a lo que Git dice, previniendo la desviacion de configuracion y proporcionando un registro de auditoria completo via git log.

### 6.3 Backup y Restore

Data Grid proporciona operaciones declarativas de Backup y Restore. Un CR de Backup crea un pod temporal que archiva todos los datos del cache en un PVC.

```bash
# 1. Comprobar los datos actuales en lookup-data
curl -sk -u ops-admin:'r3dh4t2026!' "https://${ROUTE_PF}/rest/v2/caches/lookup-data?action=size"
echo " entries in lookup-data"

# 2. Cargar datos si esta vacio
curl -sk -X POST "https://${ROUTE_REST}/api/cache/load" | jq .

# 3. Crear un backup
oc apply -f manifests/43-backup-platform.yaml -n rhdg-poc

# 4. Esperar a que el backup se complete
echo "Backup in progress..."
sleep 30
oc get backup -n rhdg-poc
```

El Operator crea un pod temporal de capacidad cero que se une al cluster, lee todos los datos del cache y escribe un archivo comprimido en un PersistentVolumeClaim. El archivo sobrevive a reinicios del cluster y puede usarse para restaurar datos en cualquier cluster compatible.

---

## Acto 7: Monitorizacion

Data Grid expone metricas de Prometheus automaticamente. El Operator crea CRs de ServiceMonitor sin configuracion manual. Combinado con Grafana, esto proporciona visibilidad en tiempo real del estado del cluster, el rendimiento del cache y la actividad de los clientes.

```bash
# Mostrar los ServiceMonitors (creados automaticamente por el Operator)
oc get servicemonitor -n rhdg-poc
```

> [!TIP]
> Abra el dashboard de Grafana en una pestana del navegador antes de comenzar la demo. Los comandos de generacion de trafico a continuacion hacen que los graficos se actualicen en tiempo real.

Abrir el dashboard de Grafana: **Grafana > Dashboards > Data Grid Metrics**

Paneles clave:
- Estado del cluster (healthcare 2/2 HEALTHY, platform 3/3 HEALTHY)
- Ratios de acierto del cache
- Latencias de lectura/escritura
- Numero de entradas por cache
- Conexiones activas de clientes

```bash
# Generar algo de trafico para que el dashboard muestre actividad
for i in $(seq 1 20); do
  curl -sk "https://${ROUTE_SPRING}/api/specialties/CAR" > /dev/null
  curl -sk "https://${ROUTE_QUARKUS}/api/specialties/NEU" > /dev/null
done
echo "20 requests sent to each app"
```

En produccion, se pueden configurar alertas sobre estas metricas: ratio de acierto del cache cayendo por debajo de un umbral, tamano del cluster cambiando inesperadamente, picos de latencia de escritura. Todo con los flujos estandar de Prometheus/Alertmanager.

---

## Acto 8: RESP con .NET y Pruebas de Carga

Este acto demuestra el consumo del protocolo RESP desde una stack tecnologica completamente diferente (.NET 10 con StackExchange.Redis) y valida el rendimiento bajo carga utilizando Gatling.

### 8.1 Iniciar la app .NET localmente

La app `dotnet-hash-test` se ejecuta localmente y se conecta al cluster Data Grid `hash-test` via Route externo con TLS passthrough.

```bash
cd apps/dotnet-hash-test
dotnet run --launch-profile http
```

La app siembra 20 Redis Hashes con 50 campos cada uno (1.000 entradas) al iniciar.

### 8.2 Verificar operaciones de cache

```bash
# Health check — confirmar conexion RESP3 con Data Grid
curl -s http://localhost:5100/health | jq .

# Single HGET — obtiene un campo de un hash
curl -s http://localhost:5100/catalog/catalog_table_000/field_0001 | jq .

# Multi-HGET — 20 consultas de hash en paralelo en una sola peticion
curl -s http://localhost:5100/catalogs | jq '{source, count, latencyMs}'
```

El campo `source` en la respuesta indica si los datos vinieron del `cache` o de la `database` (fallback a SQLite).

### 8.3 Ejecutar prueba de carga con Gatling

```bash
cd apps/gatling-load-test
mvn gatling:test
```

Tres escenarios se ejecutan concurrentemente:
1. **Single HGET** — consultas individuales al cache, ramp up hasta 10 req/s
2. **Multi HGET** — 20 consultas de hash en paralelo por peticion, hasta 5 req/s
3. **Mixed** — 80% single / 20% multi, ramp up hasta 50 req/s

### 8.4 Analizar resultados

```bash
# Metricas del lado de la app (timeouts, cache hits/misses, percentiles de latencia)
curl -s http://localhost:5100/metrics | jq .

# Informe HTML de Gatling
open apps/gatling-load-test/target/gatling/hashtestsimulation-*/index.html
```

Resultados base (2026-09-29): 3.420 peticiones, 100% éxito, p95 = 74ms, p99 = 78ms, 0 timeouts, 0 cache misses.

---

## Acto 9: Conclusion

### Lo que demostramos

| # | Requisito | Como | Resultado |
|---|-----------|------|-----------|
| 1 | Compatibilidad Redis (RESP) | Misma app, mismo redis-cli, cero cambios de codigo. Interfaz web en tiempo real con Pub/Sub + SSE | Demostrado |
| 2 | RESP Pub/Sub (cross-app) | Comparticion de cache entre multiples apps via PUBLISH/SUBSCRIBE, propagacion de eventos en tiempo real | Demostrado |
| 3 | Integracion Spring Cache | `@Cacheable` con backend Hot Rod | Demostrado |
| 4 | Integracion Quarkus | Extension `quarkus-infinispan-client` | Demostrado |
| 5 | Externalizacion Spring Session | Sesiones en Data Grid, independientes del pod | Demostrado |
| 6 | Externalizacion sesiones JBoss EAP | Capas Galleon, almacen remoto Infinispan | Demostrado |
| 7 | REST API (agnostico del lenguaje) | App ASP.NET Core, llamadas HTTP planas | Demostrado |
| 8 | Correlacion de eventos (primer caso de uso) | PENDING a DELIVERED, TTL 5 min, ASYNC | Demostrado |
| 9 | Multi-tenancy | 2 clusters aislados, credenciales por cluster | Demostrado |
| 10 | Alta disponibilidad | Pod eliminado, cero perdida de datos, recuperacion automatica | Demostrado |
| 11 | GitOps (Cache as a Service) | ArgoCD gestiona recursos, auto-repara desviaciones | Demostrado |
| 12 | Monitorizacion | Prometheus + Grafana, configuracion automatica | Demostrado |
| 13 | Backup/Restore | CR declarativo, archivo en PVC | Demostrado |
| 14 | RESP con .NET (Redis Hashes) | .NET 10 + StackExchange.Redis, HGET/HSET, patron cache-aside con fallback a SQLite | Demostrado |
| 15 | Pruebas de carga | Gatling: 3.420 peticiones, 0 timeouts, 0 cache misses, p99 78ms a 50 req/s pico | Demostrado |

### Conclusiones clave

1. **Sin cambios de codigo para aplicaciones Redis.** La transparencia del protocolo RESP significa que las aplicaciones Redis existentes funcionan tal cual.
2. **Multiples modelos de consumo.** RESP, Spring Cache, Quarkus, REST API, sesiones EAP. Un servicio, cualquier runtime.
3. **Multi-tenant por diseno.** Cada proyecto obtiene su propio cluster con credenciales y caches aislados.
4. **Listo para produccion.** HA, GitOps, monitorizacion, backup/restore, TLS, RBAC, todo integrado.
5. **Todo declarativo.** Clusters, caches y aplicaciones son manifiestos YAML gestionados por GitOps. Sin comandos imperativos en produccion.

### Proximos pasos

- **Adoptar**: Disenar el modelo operativo de "Cache as a Service" (namespaces, RBAC, flujo de onboarding)
- **Escalar**: Integrar con aplicaciones reales (comenzando con el caso de uso de correlacion de eventos)
- **Avanzado**: Replicacion cross-site para DR entre clusters (arquitectura documentada, lista para activar)

---

## Referencia Rapida: Todas las Rutas

| App | URL |
|-----|-----|
| redis-client-app | `https://redis-client-app-rhdg-poc.apps.ocp4.masales.cloud` |
| spring-cache-app | `https://spring-cache-app-rhdg-poc.apps.ocp4.masales.cloud` |
| quarkus-cache-app | `https://quarkus-cache-app-rhdg-poc.apps.ocp4.masales.cloud` |
| spring-session-app | `https://spring-session-app-rhdg-poc.apps.ocp4.masales.cloud` |
| rest-loader-app | `https://rest-loader-app-rhdg-poc.apps.ocp4.masales.cloud` |
| eap-session-app | `https://eap-session-app-rhdg-poc.apps.ocp4.masales.cloud` |
| healthcare (consola Data Grid) | `https://healthcare-external-rhdg-poc.apps.ocp4.masales.cloud` |
| platform (consola Data Grid) | `https://platform-external-rhdg-poc.apps.ocp4.masales.cloud` |

> [!CAUTION]
> Estas son credenciales de PoC. Cambiarlas antes de cualquier despliegue en produccion.

## Referencia Rapida: Credenciales

| Usuario | Contrasena | Cluster | Rol |
|---------|------------|---------|-----|
| `app-healthcare` | `h34lthc4r3!` | healthcare | application |
| `app-platform` | `pl4tf0rm!` | platform | application |
| `ops-admin` | `r3dh4t2026!` | ambos | admin |
| `default` (Redis) | `r3d1s-p0c-2026` | Redis baseline | — |
