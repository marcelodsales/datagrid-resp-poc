# Red Hat Data Grid 8.6: Cache as a Service (PoC)

Prueba de Concepto para validar **Red Hat Data Grid 8.6** como servicio de caché centralizado y multi-tenant ("Cache as a Service") en OpenShift, reemplazando o complementando Redis.

Data Grid está incluido en la suscripción **Red Hat Application Foundations**. No se requiere ninguna licencia adicional.

## Tabla de Contenidos

1. [Visión General de la Arquitectura](#1-visión-general-de-la-arquitectura)
2. [Prerrequisitos](#2-prerrequisitos)
3. [Topología de Infraestructura](#3-topología-de-infraestructura)
4. [Instalar el Operador de Data Grid](#4-instalar-el-operador-de-data-grid)
5. [GitOps con ArgoCD](#5-gitops-con-argocd)
6. [Crear Credenciales](#6-crear-credenciales)
7. [Desplegar Clústeres de Data Grid](#7-desplegar-clústeres-de-data-grid)
8. [Crear Cachés](#8-crear-cachés)
9. [Desplegar Redis de Referencia](#9-desplegar-redis-de-referencia)
10. [Cobertura del Protocolo RESP: Redis vs Data Grid](#10-cobertura-del-protocolo-resp-redis-vs-data-grid)
11. [Desplegar Aplicaciones de Prueba](#11-desplegar-aplicaciones-de-prueba)
12. [Validar Casos de Uso](#12-validar-casos-de-uso)
13. [Monitorización](#13-monitorización)
14. [Backup y Restauración](#14-backup-y-restauración)
15. [Replicación Cross-Site (Opcional)](#15-replicación-cross-site-opcional)
16. [Decisiones de Arquitectura](#16-decisiones-de-arquitectura)
17. [Referencia de Cachés](#17-referencia-de-cachés)
18. [Referencia de Aplicaciones](#18-referencia-de-aplicaciones)
19. [Referencias de Documentación](#19-referencias-de-documentación)
20. [Índice de Manifiestos](#20-índice-de-manifiestos)
21. [Matriz de Validación](#21-matriz-de-validación)

---

## 1. Visión General de la Arquitectura

![Architecture Overview](images/architecture-overview.png)

### 1.1 Modelo Multi-Tenant

Una instalación del Operador gestiona múltiples clústeres independientes de Data Grid. Cada clúster tiene:

- Su propio StatefulSet y pods
- Su propio Secret de credenciales (sin acceso cruzado)
- Su propio RBAC con roles `application` y `admin`
- Sus propias cachés con configuración independiente

Este es el modelo de aislamiento recomendado, **no** un único clúster compartido de gran tamaño.

> **Ref:** Operator Guide, Ch.4; Performance Guide, Section 2.2

---

## 2. Prerrequisitos

- Clúster OpenShift 4.x con acceso de administrador
- CLI `oc` autenticado en el clúster
- `jq` instalado (utilizado para formateo de salida JSON a lo largo de esta guía)
- Namespace para la PoC (esta guía utiliza `rhdg-poc`)
- Operador de Data Grid disponible en OperatorHub (incluido con la suscripción Application Foundations)
- Para las aplicaciones de prueba: acceso al registro de imágenes interno de OpenShift
- **OpenShift GitOps (ArgoCD)** ya instalado en el clúster (Operador + instancia de ArgoCD en el namespace `openshift-gitops`)
- **Grafana Operator** ya instalado con una instancia de Grafana configurada para leer fuentes de datos de Prometheus

> [!IMPORTANT]
> Esta PoC **no** instala ArgoCD ni Grafana. Se asume que ambos ya están ejecutándose en el clúster. ArgoCD se utiliza para la gestión de recursos basada en GitOps (Capítulo 5) y Grafana para la visualización de métricas (Capítulo 13). Si su clúster no dispone de estos operadores, instálelos desde OperatorHub antes de continuar.

### 2.1 Crear el namespace

```bash
oc new-project rhdg-poc
```

---

## 3. Topología de Infraestructura

| Componente | Detalles |
|-----------|---------|
| Versión de OpenShift | 4.x (probado en SNO) |
| Namespace | `rhdg-poc` |
| Versión de Data Grid | 8.6.0-1 |
| Canal del Operador | `stable` |
| TLS | Automático mediante certificados de servicio de OpenShift |
| Monitorización | Prometheus ServiceMonitor (creado automáticamente por el Operador) |

### 3.1 Equivalentes Upstream

Esta PoC utiliza productos de Red Hat. La siguiente tabla muestra los equivalentes upstream de la comunidad para entornos sin suscripción de Red Hat:

| Producto Red Hat | Equivalente Upstream | Versión Utilizada |
|----------------|--------------------:|-------------|
| Red Hat Data Grid 8.6 | Infinispan Server | 15.1.x |
| Data Grid Operator | Infinispan Operator | 2.4.x |
| JBoss EAP 8 | WildFly | 34.0.1.Final |
| UBI 9 / OpenJDK 21 | Eclipse Temurin 21 | — |

> La aplicación `eap-session-app` valida la externalización de sesiones de JBoss EAP 8 utilizando paquetes Galleon de WildFly 34 (upstream comunitario, comportamiento idéntico) e imágenes UBI públicas, por lo que no se requiere suscripción de Red Hat. Para Data Grid, el Infinispan Operator está disponible en [operatorhub.io](https://operatorhub.io/operator/infinispan) para Kubernetes estándar.

---

## 4. Instalar el Operador de Data Grid

### 4.1 OperatorGroup

```yaml
# manifests/01-operator-group.yaml
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: rhdg-poc-og
  namespace: rhdg-poc
spec:
  targetNamespaces:
    - rhdg-poc
```

### 4.2 Subscription

```yaml
# manifests/02-operator-subscription.yaml
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: datagrid-operator
  namespace: rhdg-poc
spec:
  channel: stable
  installPlanApproval: Automatic
  name: datagrid
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

### 4.3 Aplicar

```bash
oc apply -f manifests/01-operator-group.yaml
oc apply -f manifests/02-operator-subscription.yaml
```

### 4.4 Verificar

```bash
oc wait --for=condition=Available deployment/infinispan-operator-controller-manager \
  -n rhdg-poc --timeout=300s
```

> **Ref:** Operator Guide, Ch.1, Installing Data Grid Operator

---

## 5. GitOps con ArgoCD

> [!NOTE]
> Este capítulo configura una **Application** de ArgoCD. No instala ArgoCD. OpenShift GitOps (ArgoCD) debe estar ya ejecutándose en el namespace `openshift-gitops` (ver [Prerrequisitos](#2-prerrequisitos)).

Antes de crear cualquier recurso de Data Grid, esta sección configura el pipeline de GitOps que **gestionará y reconciliará** esos recursos automáticamente. Una vez configurado ArgoCD, cada manifiesto enviado al repositorio Git se aplica al clúster en segundos, sin necesidad de ejecutar `oc apply` manualmente.

### 5.1 Cómo Funciona GitOps

GitOps es un modelo operativo donde **Git es la única fuente de verdad** sobre lo que debe estar ejecutándose en el clúster:

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Developer   │    │  Push YAML   │    │  ArgoCD      │    │  Resources   │
│  creates or  │───►│  to Git      │───►│  detects     │───►│  created or  │
│  modifies    │    │  repository  │    │  change and  │    │  updated in  │
│  a manifest  │    │              │    │  applies it  │    │  the cluster │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
     author              commit             reconcile           running
```

- **Sincronización automática**: ArgoCD consulta el repositorio Git cada 30 segundos. Los manifiestos nuevos o modificados se aplican automáticamente.
- **Auto-reparación**: si alguien modifica un recurso directamente en el clúster (`oc edit`, `oc scale`), ArgoCD detecta la desviación y la revierte para que coincida con Git. El clúster siempre refleja lo que dice Git.
- **Trazabilidad**: cada cambio pasa por Git, registrando quién cambió qué, cuándo y por qué (mensaje del commit).

### 5.2 Repositorio Dedicado de Manifiestos

ArgoCD sincroniza desde un **repositorio Git dedicado** que contiene únicamente los manifiestos de Kubernetes que debe gestionar. Este repositorio es independiente del repositorio principal del proyecto (que también contiene código fuente de aplicaciones, Dockerfiles, documentación y manifiestos de referencia que ArgoCD NO debe reconciliar).

| Repositorio | Propósito |
|---|---|
| `datagrid-poc` | Proyecto completo de la PoC: tutorial, código fuente, TODOS los manifiestos (catálogo/referencia) |
| `datagrid-poc-manifests` | **Sincronizado por ArgoCD**: solo los 25 manifiestos que ArgoCD gestiona activamente |

> **¿Por qué separados?** Usar el repositorio principal del proyecto como fuente de ArgoCD causaba conflictos: archivos que no son de Kubernetes rompían el análisis de manifiestos, y manifiestos variantes (cross-site, backup/restore) para el mismo recurso causaban errores de recurso duplicado. El repositorio dedicado garantiza que ArgoCD solo vea lo que debe reconciliar.

### 5.3 Qué Gestiona ArgoCD

ArgoCD gestiona la infraestructura de Data Grid y las aplicaciones de prueba que la consumen:

| Categoría | Recursos |
|---|---|
| **Credenciales** | `healthcare-credentials`, `platform-credentials` |
| **Clústeres** | `healthcare` (2 réplicas), `platform` (3 réplicas) |
| **Cachés** | `event-tracking`, `app-config`, `user-sessions`, `lookup-data`, `eap-sessions` |
| **Aplicaciones** | `spring-cache-app`, `spring-session-app`, `rest-loader-app`, `eap-session-app`, `quarkus-cache-app` |
| **Soporte** | `service-ca-bundle` (ConfigMap para confianza TLS) |

**Lo que ArgoCD NO gestiona** (se aplica manualmente):

| Recurso | Motivo |
|---|---|
| Operador de Data Grid | Se instala una vez via OperatorHub; prerrequisito, no gestionado en día 2 |
| Redis de referencia | Infraestructura solo para pruebas de comparación A/B, no de producción |
| `redis-client-app` | Se parchea en vivo durante la demo RESP (`oc patch`); selfHeal lo revertiría |
| Dashboard de Grafana | Herramienta de monitorización interna |
| Manifiestos cross-site (33-38) | Opcionales; redefinen los mismos CRs, causarían conflicto con los manifiestos base |
| Backup/Restore (43-44) | Operaciones bajo demanda, no reconciliación continua |

### 5.4 Application de ArgoCD

El CR `Application` indica a ArgoCD qué vigilar y dónde aplicar:

```yaml
# manifests/32-argocd-application.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: datagrid-poc
  namespace: openshift-gitops
spec:
  project: default
  source:
    repoURL: http://gitea.openshift-gitops.svc:3000/gitea/datagrid-poc-manifests.git
    targetRevision: main
    path: .
  destination:
    server: https://kubernetes.default.svc
    namespace: rhdg-poc
  syncPolicy:
    automated:
      prune: false
      selfHeal: true
    syncOptions:
      - CreateNamespace=false
      - RespectIgnoreDifferences=true
  ignoreDifferences:
    - group: infinispan.org
      kind: Infinispan
      jsonPointers:
        - /spec
    - group: infinispan.org
      kind: Cache
      jsonPointers:
        - /spec
```

Configuraciones clave:

- **`selfHeal: true`**: si alguien modifica el clúster directamente, ArgoCD lo revierte en segundos. Este es el valor fundamental: sin cambios silenciosos.
- **`prune: false`**: eliminar un manifiesto de Git NO elimina el recurso del clúster. Red de seguridad para la PoC.
- **`ignoreDifferences`**: el Operador de Data Grid modifica el `spec` de los CRs Infinispan y Cache tras su creación (añade valores por defecto, probes, scheduling). Sin esto, ArgoCD vería los campos añadidos por el Operador como "desviación" y entraría en un bucle infinito de reversión.

### 5.5 Configuración

> [!WARNING]
> La Application de ArgoCD gestiona los Deployments de las aplicaciones (spring-cache-app, quarkus-cache-app, etc.). Estos Deployments referencian imágenes de contenedor que deben existir en el registro interno de OpenShift **antes** de que ArgoCD las sincronice. Si aplica la Application de ArgoCD ahora, los pods permanecerán en `ImagePullBackOff` hasta que construya las imágenes en el [Capítulo 11](#11-desplegar-aplicaciones-de-prueba). Esto es esperado. ArgoCD convergerá a `Healthy` una vez que todas las imágenes estén disponibles. Alternativamente, puede diferir este paso hasta después del Capítulo 11.

```bash
# Aplicar la Application de ArgoCD
oc apply -f manifests/32-argocd-application.yaml

# Verificar (puede mostrar Health: Degraded hasta que se construyan las imágenes en el Capítulo 11)
oc get application.argoproj.io datagrid-poc -n openshift-gitops \
  -o jsonpath='Sync: {.status.sync.status}, Health: {.status.health.status}'
# Esperado tras construir las imágenes: Sync: Synced, Health: Healthy
```

### 5.6 GitOps vs Manual: Cómo Leer Este Tutorial

A partir de este punto, cada capítulo crea recursos de Kubernetes mostrando el YAML del manifiesto y un comando `oc apply`. **Estos son los mismos pasos que ArgoCD realiza automáticamente** cuando se envían manifiestos al repositorio Git.

El tutorial muestra comandos `oc apply` por dos razones:

1. **Didáctica**: se comprende exactamente qué hace cada recurso y por qué existe
2. **Reproducibilidad**: se pueden seguir los pasos manualmente incluso sin ArgoCD

En el modelo GitOps, el flujo equivalente es:

```bash
# En lugar de:  oc apply -f manifests/07-cache-event-tracking.yaml
# Se hace:
cp manifests/07-cache-event-tracking.yaml /path/to/datagrid-poc-manifests/
cd /path/to/datagrid-poc-manifests
git add 07-cache-event-tracking.yaml
git commit -m "Add event-tracking cache for healthcare cluster"
git push origin main
# ArgoCD detecta el commit y aplica el manifiesto automáticamente
```

Ambos caminos producen el mismo resultado: el recurso existe en el clúster. La diferencia es **quién lo aplica**: usted (con `oc apply`) o ArgoCD (automáticamente, desde Git). En producción, el camino GitOps añade aprobación (pull requests), auditoría (git log) y protección contra desviaciones (selfHeal).

> [!NOTE]
> A lo largo de este tutorial, cada comando `oc apply` tiene un equivalente GitOps: envíe el manifiesto al repositorio `datagrid-poc-manifests` y ArgoCD lo aplica automáticamente. Los comandos manuales se muestran con fines didácticos; son exactamente lo que ArgoCD hace entre bastidores.

> **Ref:** For the full GitOps internal reference (ArgoCD credentials, Ansible integration, troubleshooting), see [docs/gitops-workflow.md](docs/gitops-workflow.md).

---

## 6. Crear Credenciales

> [!WARNING]
> Las contraseñas mostradas a continuación son para fines de la PoC. En producción, utilice contraseñas robustas y gestiónelas mediante una solución de gestión de Secrets (por ejemplo, Vault, Sealed Secrets o External Secrets Operator).

Data Grid utiliza Secrets de identidades para la autenticación. Cada clúster obtiene su propio Secret de credenciales con usuarios aislados.

### 6.1 Credenciales del clúster healthcare

```yaml
# manifests/03-healthcare-credentials.yaml
apiVersion: v1
kind: Secret
metadata:
  name: healthcare-credentials
  namespace: rhdg-poc
type: Opaque
stringData:
  identities.yaml: |
    credentials:
      - username: ops-admin
        password: r3dh4t2026!          # ← cambiar en producción
        roles:
          - admin
      - username: app-healthcare
        password: h34lthc4r3!           # ← cambiar en producción
        roles:
          - application
```

### 6.2 Credenciales del clúster platform

```yaml
# manifests/04-platform-credentials.yaml
apiVersion: v1
kind: Secret
metadata:
  name: platform-credentials
  namespace: rhdg-poc
type: Opaque
stringData:
  identities.yaml: |
    credentials:
      - username: ops-admin
        password: r3dh4t2026!          # ← cambiar en producción
        roles:
          - admin
      - username: app-platform
        password: pl4tf0rm!             # ← cambiar en producción
        roles:
          - application
```

### 6.3 Aplicar

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos Secrets ya se han aplicado automáticamente. Los comandos manuales a continuación muestran lo que hace ArgoCD; utilícelos solo si sigue el tutorial sin GitOps.

```bash
oc apply -f manifests/03-healthcare-credentials.yaml
oc apply -f manifests/04-platform-credentials.yaml
```

### 6.4 Roles RBAC

| Rol | Permisos | Propósito |
|------|-------------|---------|
| `admin` | ALL | Gestión del clúster, monitorización, administración de cachés |
| `application` | READ, WRITE, EXEC, LISTEN, BULK_READ, BULK_WRITE, CREATE, MONITOR | Acceso de aplicaciones a las cachés |

> **Ref:** Operator Guide, Ch.9, Configuring user roles and permissions

---

## 7. Desplegar Clústeres de Data Grid

### 7.1 Clúster `healthcare`

Propósito: clúster ligero para datos efímeros (correlación de eventos, configuración de aplicaciones, protocolo RESP).

```yaml
# manifests/05-cluster-healthcare.yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: healthcare
  namespace: rhdg-poc
  annotations:
    infinispan.org/monitoring: "true"
spec:
  replicas: 2
  version: 8.6.0-1
  service:
    type: DataGrid
  container:
    cpu: "500m"
    memory: "1Gi"
  security:
    endpointSecretName: healthcare-credentials
    endpointEncryption:
      type: Service
  expose:
    type: Route
```

### 7.2 Clúster `platform`

Propósito: clúster más robusto para datos duraderos (sesiones de usuario, datos de referencia, sesiones EAP). Almacenamiento persistente habilitado.

```yaml
# manifests/06-cluster-platform.yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: platform
  namespace: rhdg-poc
  annotations:
    infinispan.org/monitoring: "true"
spec:
  replicas: 3
  version: 8.6.0-1
  service:
    type: DataGrid
    container:
      storage: 1Gi
      ephemeralStorage: false
  container:
    cpu: "1000m"
    memory: "2Gi"
  security:
    endpointSecretName: platform-credentials
    endpointEncryption:
      type: Service
  expose:
    type: Route
```

### 7.3 Aplicar y verificar

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos CRs de Infinispan ya se han aplicado automáticamente. Los comandos manuales a continuación muestran lo que hace ArgoCD; utilícelos solo si sigue el tutorial sin GitOps.

```bash
oc apply -f manifests/05-cluster-healthcare.yaml
oc apply -f manifests/06-cluster-platform.yaml

# Esperar a que los clústeres estén listos
oc wait --for=condition=WellFormed infinispan/healthcare --timeout=300s
oc wait --for=condition=WellFormed infinispan/platform --timeout=300s

# Verificar
oc get infinispan

# Aumentar timeout de inactividad en la Route de healthcare (por defecto 30-60s corta conexiones RESP SUBSCRIBE)
oc patch route healthcare-external -n rhdg-poc --type merge --patch-file patches/patch-healthcare-route-timeout.yaml
```

Salida esperada:
```
NAME         REPLICAS   READY   STATUS
healthcare   2          2       WellFormed
platform     3          3       WellFormed
```

### 7.4 Puntos Clave de Diseño

| Propiedad | healthcare | platform |
|----------|-----------|----------|
| Réplicas | 2 | 3 |
| CPU | 500m | 1000m |
| Memoria | 1Gi | 2Gi |
| Almacenamiento persistente | No | Sí (1Gi) |
| TLS | Automático (Service) | Automático (Service) |
| Endpoint | Route (HTTPS) | Route (HTTPS) |
| RESP habilitado | Sí (automático) | No |

> **Ref:** Operator Guide, Ch.4, Creating Data Grid clusters; Performance Guide, Section 2.2

---

## 8. Crear Cachés

El modo de caché se configura por caché, no por clúster. Un único clúster puede tener cachés distribuidas y replicadas simultáneamente.

### 8.1 event-tracking (healthcare)

Caché distribuida para datos temporales de correlación de eventos. TTL de 5 minutos. Utiliza modo de replicación **ASYNC**: las escrituras retornan al cliente tan pronto como el propietario primario persiste la entrada; la replicación al segundo propietario ocurre en segundo plano. Esto maximiza el rendimiento de escritura para flujos de eventos de alto volumen donde la latencia de submilisegundos importa más que la consistencia inmediata garantizada entre todos los propietarios.

```yaml
# manifests/07-cache-event-tracking.yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: event-tracking
  namespace: rhdg-poc
spec:
  clusterName: healthcare
  name: event-tracking
  template: |
    distributedCache:
      mode: "ASYNC"
      owners: "2"
      statistics: "true"
      encoding:
        mediaType: "application/x-protostream"
      expiration:
        lifespan: "300000"
      memory:
        maxCount: "100000"
        whenFull: "REMOVE"
```

> **¿Por qué ASYNC aquí?** La caché `event-tracking` almacena IDs de correlación temporales con un TTL de 5 minutos. Si un nodo falla entre la escritura primaria y la replicación asíncrona, como máximo se pierde una entrada en vuelo, y la correlación simplemente expira y se reintenta. El compromiso es aceptable: la latencia de escritura se reduce significativamente (sin ida y vuelta al propietario de respaldo), y los datos son efímeros por diseño. Para cachés donde cada escritura **debe** ser confirmada en todos los propietarios antes de retornar (por ejemplo, sesiones, datos de referencia), utilice SYNC.

### 8.2 app-config (healthcare)

Caché replicada para datos de configuración/referencia con alta carga de lectura. TTL de 10 minutos.

```yaml
# manifests/08-cache-app-config.yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: app-config
  namespace: rhdg-poc
spec:
  clusterName: healthcare
  name: app-config
  template: |
    replicatedCache:
      mode: "SYNC"
      statistics: "true"
      encoding:
        mediaType: "application/x-protostream"
      expiration:
        lifespan: "600000"
      memory:
        maxCount: "10000"
        whenFull: "REMOVE"
```

### 8.3 user-sessions (platform)

Caché distribuida para sesiones HTTP de Spring externalizadas. TTL de 10 minutos.

```yaml
# manifests/09-cache-user-sessions.yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: user-sessions
  namespace: rhdg-poc
spec:
  clusterName: platform
  name: user-sessions
  template: |
    distributedCache:
      mode: "SYNC"
      owners: "2"
      statistics: "true"
      encoding:
        mediaType: "application/x-protostream"
      expiration:
        lifespan: "600000"
      memory:
        maxCount: "100000"
        whenFull: "REMOVE"
```

### 8.4 lookup-data (platform)

Caché replicada con almacén de archivos persistente y **pasivación** habilitada para datos de referencia duraderos. TTL de 10 minutos. Cuando el recuento de entradas en memoria alcanza `maxCount` (10.000), las entradas desalojadas se escriben en disco en lugar de descartarse. Cuando se accede nuevamente a una entrada pasivada, Data Grid la carga de vuelta en memoria automáticamente. Esto crea un modelo de almacenamiento de dos niveles: datos calientes en memoria, datos templados en disco.

```yaml
# manifests/10-cache-lookup-data.yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: lookup-data
  namespace: rhdg-poc
spec:
  clusterName: platform
  name: lookup-data
  template: |
    replicatedCache:
      mode: "SYNC"
      statistics: "true"
      encoding:
        mediaType: "application/x-protostream"
      expiration:
        lifespan: "600000"
      memory:
        maxCount: "10000"
        whenFull: "REMOVE"
      persistence:
        passivation: "true"
        fileStore: ~
```

> **¿Por qué pasivación aquí?** Los datos de referencia (códigos hospitalarios, tablas de consulta, etc.) suelen ser grandes en volumen pero se acceden de forma desigual. Sin pasivación, alcanzar `maxCount` simplemente elimina las entradas menos utilizadas, y la siguiente solicitud de esos datos requiere una recarga completa desde el sistema origen. Con pasivación, las entradas desalojadas sobreviven en disco y se reactivan de forma transparente al acceder, evitando recargas costosas. El `whenFull: "REMOVE"` sigue aplicándose al nivel **en memoria**: elimina la entrada de memoria y la pasiva a disco. Sin pasivación, REMOVE descartaría permanentemente la entrada.
>
> **Pasivación vs. write-through:** Con `passivation: "true"`, el almacén de archivos es un **desbordamiento pasivo**, donde las entradas solo van a disco cuando se desalojan de memoria. Con `passivation: "false"` (u omitido), el almacén de archivos es **write-through**, donde cada escritura va tanto a memoria como a disco simultáneamente. Write-through proporciona durabilidad (los datos sobreviven a un reinicio completo del clúster) pero duplica la E/S de escritura. La pasivación proporciona expansión de capacidad (más entradas de las que caben en RAM) con menor E/S de escritura. Las otras cachés de esta PoC (`user-sessions`, `eap-sessions`, `event-tracking`) utilizan REMOVE sin persistencia; las entradas desalojadas simplemente se pierden, lo cual es aceptable para sesiones y datos de correlación temporales que se recrearán.

### 8.5 eap-sessions (platform)

Caché distribuida para sesiones HTTP de JBoss EAP externalizadas. TTL de 10 minutos.

```yaml
# manifests/11-cache-eap-sessions.yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: eap-sessions
  namespace: rhdg-poc
spec:
  clusterName: platform
  name: eap-sessions.war
  template: |
    distributedCache:
      mode: "SYNC"
      owners: "2"
      statistics: "true"
      encoding:
        mediaType: "application/x-protostream"
      expiration:
        lifespan: "600000"
      memory:
        maxCount: "100000"
        whenFull: "REMOVE"
```

### 8.6 Aplicar

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos CRs de Cache ya se han aplicado automáticamente. Los comandos manuales a continuación muestran lo que hace ArgoCD; utilícelos solo si sigue el tutorial sin GitOps.

```bash
oc apply -f manifests/07-cache-event-tracking.yaml
oc apply -f manifests/08-cache-app-config.yaml
oc apply -f manifests/09-cache-user-sessions.yaml
oc apply -f manifests/10-cache-lookup-data.yaml
oc apply -f manifests/11-cache-eap-sessions.yaml

# Verificar
oc get caches
```

### 8.7 Distribuida vs Replicada

| Modo | Distribución de datos | Ideal para |
|------|-------------------|----------|
| **Distribuida** | Datos repartidos entre `owners` nodos (subconjunto) | Grandes conjuntos de datos, escritura intensiva, escalable |
| **Replicada** | Copia completa en cada nodo | Conjuntos de datos pequeños, lectura intensiva, lecturas más rápidas |

> **Ref:** Cache Configuration Guide, Ch.2; Performance Guide, Section 2.2

### 8.8 Replicación SYNC vs ASYNC

> [!NOTE]
> El modo de replicación (`SYNC`/`ASYNC`) controla **consistencia vs latencia** entre propietarios de caché dentro del mismo clúster. Es independiente del modo de caché (distribuida/replicada) y la configuración de persistencia.

| Aspecto | SYNC | ASYNC |
|--------|------|-------|
| **La escritura retorna cuando** | Todos los propietarios han confirmado | El propietario primario ha escrito |
| **Consistencia** | Fuerte: todos los propietarios siempre tienen el último valor | Eventual: el propietario de respaldo puede tener un retraso de milisegundos |
| **Latencia de escritura** | Mayor: incluye ida y vuelta de red a los propietarios de respaldo | Menor: sin espera por confirmación del respaldo |
| **Riesgo ante fallo de nodo** | Cero pérdida de datos: el propietario de respaldo ya tiene la entrada | Posible pérdida de entradas en vuelo aún no replicadas |
| **Usar cuando** | Los datos no pueden perderse ni recrearse (sesiones, datos de referencia) | Los datos son efímeros, reintentables o de alto volumen (eventos, métricas) |

En esta PoC, `event-tracking` utiliza ASYNC porque los eventos de correlación son temporales (TTL de 5 min) y pueden reintentarse. Todas las demás cachés utilizan SYNC porque las sesiones y datos de referencia deben ser consistentes entre propietarios.

> **Ref:** Cache Configuration Guide, Ch.2, Clustering modes; Performance Guide, Section 2.3, Synchronous/asynchronous replication

### 8.9 Estrategias de Desalojo y Persistencia

> [!IMPORTANT]
> Cada caché **debe** tener un límite de memoria `maxCount` o `maxSize`. Sin él, la caché crece sin límite hasta que la JVM se queda sin heap y el pod es terminado por OOMKill.

Cuando una caché alcanza su límite de memoria `maxCount`, Data Grid debe decidir qué hacer con la entrada que se desaloja. La configuración `whenFull` controla esto:

| Estrategia | `whenFull` | `persistence` | Comportamiento |
|----------|------------|---------------|----------|
| **Desalojar y descartar** | `REMOVE` | ninguna | La entrada se elimina permanentemente. El siguiente acceso retorna un miss. |
| **Desalojar y pasivar** | `REMOVE` | `passivation: "true"` + almacén | La entrada se elimina de memoria y se escribe en disco. El siguiente acceso la carga de vuelta desde disco (activación). |
| **Write-through** | `REMOVE` | `passivation: "false"` + almacén | Cada escritura va a memoria Y disco simultáneamente. El desalojo elimina solo de memoria; el disco aún la tiene. Sobrevive a un reinicio completo del clúster. |
| **Rechazar** | `EXCEPTION` | — | La escritura se rechaza con un error. Protege la memoria a costa de la disponibilidad. |

**Qué estrategia para cada caso de uso:**

- **Sesiones** (`user-sessions`, `eap-sessions`): REMOVE sin persistencia. Las sesiones se recrean al iniciar sesión. Perder una sesión expirada es aceptable.
- **Eventos temporales** (`event-tracking`): REMOVE sin persistencia. Los eventos son efímeros y reintentables.
- **Datos de referencia** (`lookup-data`): REMOVE con pasivación. Recargar datos de referencia desde el sistema origen es costoso; la pasivación lo evita manteniendo las entradas desalojadas en disco.
- **Configuración** (`app-config`): REMOVE sin persistencia. Conjunto de datos pequeño, replicado en todos los nodos, improbable que alcance `maxCount`.

> **Ref:** Cache Configuration Guide, Ch.5, Eviction and expiration; Cache Configuration Guide, Ch.6, Persistence and cache stores

---

## 9. Desplegar Redis de Referencia

> [!IMPORTANT]
> Redis es **solo una referencia para pruebas**. Existe para demostrar la transparencia del protocolo RESP mediante comparación A/B con Data Grid. NO está gestionado por ArgoCD y NO forma parte de la arquitectura de producción.

Se despliega una instancia mínima de Redis como referencia para la validación de transparencia del protocolo RESP. La aplicación `redis-client-app` primero apunta a Redis, luego se reconfigura para apuntar a Data Grid, demostrando que las aplicaciones Redis existentes funcionan con Data Grid **sin cambios de código**.

### 9.1 Secret de Redis

```yaml
# manifests/12-redis-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: redis-secret
  namespace: rhdg-poc
type: Opaque
stringData:
  password: r3d1s-p0c-2026             # ← cambiar en producción
```

### 9.2 Deployment de Redis

```yaml
# manifests/13-redis-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: rhdg-poc
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
        role: poc-baseline
    spec:
      securityContext:
        runAsNonRoot: true
      containers:
        - name: redis
          image: docker.io/bitnami/redis:latest
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: redis-secret
                  key: password
            - name: REDIS_AOF_ENABLED
              value: "no"
            - name: REDIS_EXTRA_FLAGS
              value: "--maxmemory 200mb --maxmemory-policy allkeys-lru --tcp-backlog 128 --timeout 300 --tcp-keepalive 60 --hz 10 --loglevel notice --databases 2 --save ''"
          ports:
            - containerPort: 6379
              name: redis
          resources:
            requests:
              cpu: "100m"
              memory: "256Mi"
            limits:
              cpu: "250m"
              memory: "256Mi"
          readinessProbe:
            exec:
              command: ["/bin/sh", "-c", "redis-cli -a \"$REDIS_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command: ["/bin/sh", "-c", "redis-cli -a \"$REDIS_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
            initialDelaySeconds: 10
            periodSeconds: 15
```

**Flags de configuración de Redis explicados:**

| Flag | Valor | Propósito |
|------|-------|---------|
| `--maxmemory` | 200mb | Limitar el uso de memoria dentro de los límites del contenedor |
| `--maxmemory-policy` | allkeys-lru | Desalojar las claves menos usadas recientemente cuando esté lleno |
| `--tcp-backlog` | 128 | Backlog de conexiones (apropiado para PoC) |
| `--timeout` | 300 | Cerrar conexiones inactivas tras 5 min |
| `--tcp-keepalive` | 60 | Detectar conexiones muertas cada 60s |
| `--hz` | 10 | Frecuencia del bucle de eventos del servidor (por defecto) |
| `--databases` | 2 | Solo se necesitan 2 bases de datos lógicas |
| `--save ''` | — | Deshabilitar snapshots RDB (sin persistencia) |

### 9.3 Service de Redis

```yaml
# manifests/14-redis-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: rhdg-poc
spec:
  selector:
    app: redis
  ports:
    - port: 6379
      targetPort: 6379
  type: ClusterIP
```

### 9.4 Aplicar

```bash
oc apply -f manifests/12-redis-secret.yaml
oc apply -f manifests/13-redis-deployment.yaml
oc apply -f manifests/14-redis-service.yaml
```

### 9.5 Notas Importantes

- Utilice `bitnami/redis:latest` (Bitnami eliminó las etiquetas de versión cortas)
- NO establezca `runAsUser` o `fsGroup` explícitamente (OpenShift asigna el UID del rango del namespace)
- Redis se despliega como **referencia solo para comparación**, no como servicio de producción

---

## 10. Cobertura del Protocolo RESP: Redis vs Data Grid

Data Grid 8.6 implementa el protocolo RESP3, permitiendo a los clientes Redis existentes (Jedis, Lettuce, redis-cli) conectarse sin cambios de código. La siguiente tabla mapea los casos de uso más comunes de Redis + Spring Boot al soporte RESP de Data Grid.

### 10.1 Casos de Uso Soportados

Estos cubren la gran mayoría del uso de Redis en aplicaciones Spring Boot:

| # | Caso de Uso | Comandos Redis | Data Grid RESP |
|---|----------|---------------|----------------|
| 1 | **Caché (clave-valor)**: `@Cacheable`, almacenar/recuperar datos | GET, SET, DEL, MGET, MSET, EXISTS, GETSET, GETEX, GETDEL | Soportado |
| 2 | **TTL / Expiración**: datos temporales con tiempo de vida | EXPIRE, PEXPIRE, TTL, PTTL, PERSIST, EXPIREAT, EXPIRETIME, PEXPIREAT, PEXPIRETIME | Soportado |
| 3 | **Gestión de Sesiones**: sesiones HTTP externalizadas | HSET, HGET, HGETALL, HDEL, HMSET, HMGET, HEXISTS, HKEYS, HVALS, HLEN | Soportado |
| 4 | **Contadores / Rate Limiting**: contadores atómicos | INCR, INCRBY, DECR, DECRBY, INCRBYFLOAT, HINCRBY, HINCRBYFLOAT | Soportado |
| 5 | **Almacenamiento Hash**: perfiles de usuario, objetos estructurados | HSET, HGET, HGETALL, HMSET, HMGET, HDEL, HKEYS, HVALS, HLEN, HEXISTS, HSETNX, HRANDFIELD, HSTRLEN | Soportado |
| 6 | **Listas / Colas**: colas de tareas, buffers de mensajes | LPUSH, RPUSH, LPOP, RPOP, BLPOP, BRPOP, LRANGE, LLEN, LINDEX, LINSERT, LREM, LSET, LTRIM, LPOS, LMOVE, LMPOP, BLMPOP | Soportado |
| 7 | **Pub/Sub**: notificaciones en tiempo real, difusión de eventos | PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBSUB CHANNELS, PUBSUB NUMPAT | Soportado |
| 8 | **Sets**: etiquetas, visitantes únicos, relaciones | SADD, SMEMBERS, SISMEMBER, SINTER, SUNION, SDIFF, SREM, SCARD, SPOP, SRANDMEMBER, SMOVE, SMISMEMBER, SINTERCARD, SINTERSTORE, SUNIONSTORE, SDIFFSTORE | Soportado |
| 9 | **Sorted Sets / Rankings**: clasificaciones, prioridades, programación | ZADD, ZRANGE, ZRANGEBYSCORE, ZRANGEBYLEX, ZRANK, ZREM, ZSCORE, ZCARD, ZCOUNT, ZINCRBY, ZPOPMAX, ZPOPMIN, ZMPOP, ZRANDMEMBER, ZREVRANGE, ZREVRANGEBYSCORE, ZREVRANK, ZLEXCOUNT, ZRANGESTORE, ZDIFF, ZDIFFSTORE, ZINTER, ZINTERCARD, ZINTERSTORE, ZUNION, ZUNIONSTORE, ZMSCORE, ZREMRANGEBYSCORE, ZREMRANGEBYRANK, ZREMRANGEBYLEX | Soportado |
| 10 | **Bloqueos Distribuidos**: `SET key value NX EX` | SET (con flags NX, EX, PX) | Soportado |
| 11 | **Transacciones**: operaciones atómicas multi-clave | MULTI, EXEC, DISCARD, WATCH, UNWATCH | Soportado |
| 12 | **Scripting Lua**: lógica en el servidor | EVAL, EVALSHA, EVAL_RO, EVALSHA_RO, SCRIPT LOAD, SCRIPT EXISTS, SCRIPT FLUSH | Soportado |
| 13 | **Documentos JSON**: almacenar/consultar JSON nativamente | JSON.SET, JSON.GET, JSON.DEL, JSON.MERGE, JSON.MGET, JSON.MSET, JSON.ARRAPPEND, JSON.ARRINDEX, JSON.ARRINSERT, JSON.ARRLEN, JSON.ARRTRIM, JSON.ARRPOP, JSON.CLEAR, JSON.NUMINCRBY, JSON.NUMMULTBY, JSON.OBJKEYS, JSON.OBJLEN, JSON.RESP, JSON.STRAPPEND, JSON.STRLEN, JSON.TOGGLE, JSON.TYPE, JSON.DEBUG MEMORY | Soportado |
| 14 | **HyperLogLog**: conteo de cardinalidad aproximada | PFADD | Soportado |
| 15 | **Manipulación de strings**: append, range, length | APPEND, GETRANGE, SETRANGE, STRLEN, STRALGO LCS, LCS | Soportado |
| 16 | **Gestión de claves**: scan, rename, type, sort | KEYS, SCAN, RANDOMKEY, RENAME, RENAMENX, TYPE, TOUCH, SORT, SORT_RO, DBSIZE, FLUSHDB, FLUSHALL, SELECT | Soportado |

### 10.2 No Soportado vía RESP

Estas funcionalidades de Redis **no tienen equivalente** en el endpoint RESP de Data Grid:

| Funcionalidad | Comandos Redis | Caso de Uso Típico |
|---------|---------------|------------------|
| **Streams** | XADD, XREAD, XREADGROUP, XLEN, XRANGE, XACK, XDEL, XTRIM, XINFO, XGROUP | Streaming de eventos, grupos de consumidores, datos append-only tipo log |
| **Geoespacial** | GEOADD, GEODIST, GEOHASH, GEOPOS, GEORADIUS, GEOSEARCH, GEOMEMBERS | Consultas basadas en ubicación, búsqueda por proximidad |
| **Bitmaps** | BITCOUNT, BITOP, BITFIELD, BITPOS, GETBIT, SETBIT | Feature flags, analítica en tiempo real, filtros bloom |

Estas son funcionalidades de nicho. La gran mayoría de las aplicaciones Spring Boot + Redis utilizan solo las operaciones soportadas listadas anteriormente.

### 10.3 Diferencias Clave

| Aspecto | Redis | Data Grid |
|--------|-------|-----------|
| Versión del protocolo | RESP2 + RESP3 | Solo RESP3 |
| Aislamiento | Serializable (single-threaded) | Relajado (configurable) |
| Transacciones | Sin rollback en caso de fallo | ACID con rollback |
| Scripts Lua | Almacenados en caché de scripts limitada | Ejecutados y descartados inmediatamente |
| Bases de datos lógicas | Solo database 0 en modo Cluster | SELECT funciona tanto en modo standalone como clustered |
| Clustering | Requiere redirecciones -MOVED desde los clientes | Routing automático, sin redirecciones necesarias |

> **Ref:** Data Grid 8.6, [Using the RESP protocol endpoint with Data Grid](https://access.redhat.com/documentation/en-us/red_hat_data_grid/8.6/html/using_the_resp_protocol_endpoint_with_data_grid/), Ch.1, Sections 1.3 and 1.4

---

## 11. Desplegar Aplicaciones de Prueba

Todos los manifiestos de Deployment referencian imágenes pre-construidas de `quay.io/masales/<app>:v1.0`. No se requiere compilación local.

> [!IMPORTANT]
> Si ArgoCD fue configurado en el Capítulo 5, ya ha creado los Deployments, Services y Routes para las aplicaciones gestionadas. Los pods descargarán las imágenes directamente desde quay.io. Redis (Capítulo 9) debe estar ejecutándose antes de que `redis-client-app` pueda conectarse.

| App | Runtime | Imagen |
|-----|---------|-------|
| `redis-client-app` | Spring Boot 3 | `quay.io/masales/redis-client-app:v1.0` |
| `spring-cache-app` | Spring Boot 3 | `quay.io/masales/spring-cache-app:v1.0` |
| `spring-session-app` | Spring Boot 3 | `quay.io/masales/spring-session-app:v1.0` |
| `rest-loader-app` | ASP.NET Core 10 | `quay.io/masales/rest-loader-app:v1.0` |
| `eap-session-app` | JBoss EAP 8 | `quay.io/masales/eap-session-app:v1.0` |
| `quarkus-cache-app` | Quarkus 3 | `quay.io/masales/quarkus-cache-app:v1.0` |

> **Nota:** `eap-session-app` valida la externalización de sesiones de **JBoss EAP 8** a Data Grid. La PoC utiliza WildFly 34 (upstream comunitario, código y configuración idénticos) con imágenes UBI públicas, por lo que no se requiere suscripción de Red Hat para la compilación. Para producción, reemplace las imágenes base del Dockerfile con `registry.redhat.io/jboss-eap-8/eap8-openjdk21-builder-openshift-rhel9` (requiere `podman login registry.redhat.io`). `rest-loader-app` utiliza ASP.NET Core 10 en UBI9, demostrando que la API REST de Data Grid es agnóstica al lenguaje.

### 11.1 Compilación desde código fuente (opcional)

Si prefiere compilar las imágenes usted mismo en lugar de usar las pre-construidas de quay.io, cada aplicación tiene un `Dockerfile` y código fuente en `apps/<app-name>/`. Todos los Dockerfiles utilizan **compilaciones multi-stage**: Maven se ejecuta dentro del contenedor, por lo que no se requiere instalación local de JDK o Maven.

```bash
# 1. Crear un BuildConfig (solo la primera vez)
oc new-build --name=<app-name> --binary --strategy=docker -n rhdg-poc

# 2. Compilar y enviar la imagen al registro interno
oc start-build <app-name> --from-dir=apps/<app-name> --follow -n rhdg-poc
```

Compilar todas las aplicaciones en secuencia:

```bash
for APP in redis-client-app spring-cache-app spring-session-app rest-loader-app eap-session-app quarkus-cache-app; do
  echo "=== Building ${APP} ==="
  oc new-build --name=${APP} --binary --strategy=docker -n rhdg-poc 2>/dev/null || true
  oc start-build ${APP} --from-dir=apps/${APP} --follow -n rhdg-poc
done
```

Tras la compilación, actualice los manifiestos de Deployment para referenciar el registro interno:

```
image: image-registry.openshift-image-registry.svc:5000/rhdg-poc/<app-name>:latest
```

| App | Base del Dockerfile | Código Fuente |
|-----|----------------|--------|
| `redis-client-app` | `registry.access.redhat.com/ubi9/openjdk-25-runtime` | `apps/redis-client-app/` |
| `spring-cache-app` | `registry.access.redhat.com/ubi9/openjdk-21-runtime` | `apps/spring-cache-app/` |
| `spring-session-app` | `registry.access.redhat.com/ubi9/openjdk-21-runtime` | `apps/spring-session-app/` |
| `rest-loader-app` | `registry.access.redhat.com/ubi9/dotnet-100-runtime` | `apps/rest-loader-app/` |
| `eap-session-app` | `registry.access.redhat.com/ubi9/openjdk-21-runtime` | `apps/eap-session-app/` |
| `quarkus-cache-app` | `registry.access.redhat.com/ubi9/openjdk-21-runtime` | `apps/quarkus-cache-app/` |

### 11.2 redis-client-app (Protocolo RESP)

Valida la transparencia del protocolo RESP. Spring Boot + cliente Jedis, desplegado inicialmente contra Redis (puerto 6379) para demostrar que la aplicación funciona como se espera. Luego se parchea en vivo para conectarse al endpoint RESP de Data Grid (puerto 11222), demostrando que el mismo código de aplicación funciona contra ambos backends sin cambios. La imagen incluye `redis-cli` para validación interactiva a nivel de protocolo.

**Funcionalidades:**

- **Interfaz Web** — Monitor de caché en tiempo real en la URL de la Route (`https://redis-client-app-rhdg-poc.apps.ocp4.masales.cloud`). Muestra metadatos de conexión (clúster, nombre de caché, modo, host, puerto, estado), entradas de caché con key/value/type/TTL, y un log de eventos en vivo.
- **Pub/Sub + SSE** — La aplicación publica eventos de mutación (SET, DEL) en el canal Pub/Sub RESP `cache-events`. Un subscriber en el backend retransmite los eventos al navegador via Server-Sent Events (SSE). Cualquier cliente que publique en el mismo canal (p.ej., `external-cache-client`) tiene sus eventos reflejados en la UI automáticamente.
- **Formulario** — Operaciones SET y DEL directamente desde el navegador. FLUSH elimina todas las entradas. El `respCache` tiene un TTL por defecto a nivel de caché de 300s (5 min), mostrado en la interfaz.
- **Visibilidad cross-app** — El `external-cache-client` (Quarkus, se ejecuta localmente) se conecta a Data Grid via la Route RESP externa y también publica en `cache-events`. Sus escrituras aparecen en la UI de `redis-client-app` en tiempo real, demostrando el uso compartido de caché entre múltiples aplicaciones via Pub/Sub RESP.

```bash
oc apply -f manifests/15-redis-client-app-deployment.yaml
oc apply -f manifests/16-redis-client-app-service.yaml
oc apply -f manifests/17-redis-client-app-route.yaml
```

> [!WARNING]
> Esta aplicación se despliega manualmente (`oc apply`) y **NO está gestionada por ArgoCD**. Esto es intencional: durante la demo de transparencia RESP, el deployment se parchea en vivo con `oc patch` para alternar entre backends Redis y Data Grid. El `selfHeal` de ArgoCD revertería estos parches inmediatamente.

**Conexión por defecto (Redis):**

| Parámetro | Valor |
|-----------|-------|
| Host | `redis` (Service de Redis de referencia) |
| Puerto | `6379` |
| Usuario | `default` |
| SSL | `false` |

Durante la demo, utilice los archivos de parche en `patches/` para alternar backends:

```bash
# Cambiar a Data Grid
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-datagrid.yaml

# Volver a Redis
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-redis.yaml
```

La aplicación utiliza el `respCache`, creado automáticamente por Data Grid con codificación `application/octet-stream` y `RESPHashFunctionPartitioner`. No se necesita ninguna caché personalizada para RESP.

> **Ref:** RESP Protocol Guide, Ch.1, Section 1.1

### 11.3 spring-cache-app (Spring Cache)

Valida Data Grid como proveedor de Spring Cache usando anotaciones `@Cacheable`. Se conecta al clúster `healthcare` via Hot Rod, utiliza la caché `app-config`.

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos recursos ya se han aplicado automáticamente.

```bash
oc apply -f manifests/18-spring-cache-app-deployment.yaml
oc apply -f manifests/19-spring-cache-app-service.yaml
oc apply -f manifests/20-spring-cache-app-route.yaml
```

> **Ref:** Spring Boot Starter Guide, Spring Cache with remote server

### 11.4 quarkus-cache-app (Quarkus Infinispan Client)

Valida dos casos de uso utilizando Quarkus con la extensión `quarkus-infinispan-client`, conectándose al clúster `healthcare` via Hot Rod:

- **Proveedor de caché** (`/api/specialties`): mismo caso de uso que `spring-cache-app`, utilizando la caché `app-config` con marshalling ProtoStream. Demuestra que Data Grid soporta múltiples runtimes de forma transparente.
- **Correlación de eventos** (`/api/events`): demuestra el almacenamiento temporal de IDs de correlación para acuse de recibo de entrega de mensajes asíncronos, utilizando la caché `event-tracking` (distribuida, TTL de 5 min). Demuestra Data Grid como almacén de correlación para flujos de mensajes asíncronos.

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos recursos ya se han aplicado automáticamente.

```bash
oc apply -f manifests/40-quarkus-cache-app-deployment.yaml
oc apply -f manifests/41-quarkus-cache-app-service.yaml
oc apply -f manifests/42-quarkus-cache-app-route.yaml
```

La imagen ya se compiló mediante el script por lotes en la [Sección 11.1](#111-compilación-desde-código-fuente-opcional).

> **Ref:** Quarkus Infinispan Client Guide, https://quarkus.io/guides/infinispan-client/

### 11.5 spring-session-app (Spring Session)

Valida la gestión de sesiones HTTP externalizadas con `@EnableInfinispanRemoteHttpSession`. Se conecta al clúster `platform`, utiliza la caché `user-sessions`.

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos recursos ya se han aplicado automáticamente.

```bash
oc apply -f manifests/21-spring-session-app-deployment.yaml
oc apply -f manifests/22-spring-session-app-service.yaml
oc apply -f manifests/23-spring-session-app-route.yaml
```

> **Ref:** Spring Boot Starter Guide, Spring Session with remote server

### 11.6 rest-loader-app (API REST)

Valida la API REST de Data Grid para cargar datos de referencia. Desarrollada con **ASP.NET Core 10** (C#) y `HttpClient`, sin bibliotecas cliente de Infinispan. Solo llamadas HTTP simples al endpoint REST de Data Grid. Se conecta al clúster `platform`, escribe en la caché `lookup-data`. Demuestra que la API REST de Data Grid es agnóstica al lenguaje; cualquier cliente HTTP funciona.

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos recursos ya se han aplicado automáticamente.

```bash
oc apply -f manifests/24-rest-loader-app-deployment.yaml
oc apply -f manifests/25-rest-loader-app-service.yaml
oc apply -f manifests/26-rest-loader-app-route.yaml
```

> **Ref:** REST API Guide

### 11.7 eap-session-app (Externalización de Sesiones JBoss EAP)

Valida la externalización de sesiones HTTP de **JBoss EAP 8** a Data Grid. Utiliza las capas Galleon `cloud-server` + `web-clustering` y un almacén de sesiones remoto de Infinispan. Se conecta al clúster `platform`, utiliza la caché `eap-sessions` (nombre de caché: `eap-sessions.war`).

> La PoC compila con WildFly 34 (upstream comunitario de JBoss EAP 8) por conveniencia (imágenes públicas, sin necesidad de suscripción). El mecanismo de externalización de sesiones, el código y la configuración son idénticos a JBoss EAP 8.

Requiere el bundle CA del servicio de OpenShift para la confianza TLS:

```yaml
# manifests/27a-service-ca-bundle.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: service-ca-bundle
  namespace: rhdg-poc
  annotations:
    service.beta.openshift.io/inject-cabundle: "true"
data: {}
```

> [!TIP]
> Si ArgoCD está configurado (Capítulo 5), estos recursos ya se han aplicado automáticamente.

```bash
oc apply -f manifests/27a-service-ca-bundle.yaml
oc apply -f manifests/27-eap-session-app-deployment.yaml
oc apply -f manifests/28-eap-session-app-service.yaml
oc apply -f manifests/29-eap-session-app-route.yaml
```

> **Ref:** Data Grid Operator Guide, Ch.7, Configuring JBoss EAP applications

### 11.8 dotnet-hash-test (RESP + Redis Hashes + Pruebas de Carga)

Valida el protocolo RESP con **.NET 10** usando `StackExchange.Redis` y operaciones Redis Hash (`HGET`/`HSET`). Implementa un patrón cache-aside: la app intenta el caché primero y recurre a una base de datos SQLite embebida en caso de timeout o miss. Incluye pruebas de carga con Gatling para estresar el endpoint RESP bajo acceso concurrente.

Esta app se ejecuta **localmente** (no se despliega en OpenShift). Se conecta al clúster Data Grid `hash-test` a través de una Route passthrough externa.

**Configuración del clúster:**

El clúster `hash-test` usa una configuración de servidor basada en ConfigMap (no Cache CR) con un caché llamado `redis-cache`, configurado con particionador CRC16, codificación `octet-stream`, `fileStore` habilitado y conector RESP.

```bash
# Desplegar el clúster hash-test
oc apply -f manifests/50-hashtest-credentials.yaml
oc apply -f manifests/52-hashtest-server-config.yaml
oc apply -f manifests/51-cluster-hash-test.yaml
oc wait --for=condition=WellFormed infinispan/hash-test --timeout=300s

# Crear Route passthrough (el Operator crea Ingress, no Route en OCP)
oc create route passthrough hash-test-external \
  --service=hash-test \
  --port=11222 \
  --hostname=hash-test.apps.ocp4.masales.cloud
```

**Ejecutar la app .NET:**

```bash
cd apps/dotnet-hash-test
dotnet run --launch-profile http
# Escucha en http://localhost:5100
```

Al iniciar, la app carga 20 Redis Hashes con 50 campos cada uno (1.000 entradas en total) tanto en el caché como en SQLite con datos determinísticos idénticos.

**Endpoints:**

| Endpoint | Descripción |
|----------|-------------|
| `GET /catalog/{table}/{field}` | HGET individual con fallback a SQLite |
| `GET /catalogs` | Multi-HGET (20 HashGetAsync en paralelo) con fallback a SQLite |
| `GET /metrics` | Contadores, percentiles de latencia caché/db, últimos 20 timeouts |
| `GET /metrics/reset` | Resetear todos los contadores |
| `GET /health` | Estado de la conexión |

**Prueba de humo:**

```bash
# Verificar salud
curl -s http://localhost:5100/health | jq .

# HGET individual
curl -s http://localhost:5100/catalog/catalog_table_000/field_0001 | jq .

# Multi-HGET (20 en paralelo)
curl -s http://localhost:5100/catalogs | jq '{source, count, latencyMs}'

# Métricas después de algunas peticiones
curl -s http://localhost:5100/metrics | jq .
```

**Pruebas de carga con Gatling:**

Una simulación Gatling en `apps/gatling-load-test/` genera carga HTTP contra la API .NET con tres escenarios:

1. **HGET individual** — rampa 1→10 req/s, luego constante 10 req/s
2. **Multi HGET (20 en paralelo)** — rampa 1→5 req/s, luego constante 5 req/s
3. **Carga mixta** — 80% individual / 20% multi, rampa hasta 50 req/s

```bash
cd apps/gatling-load-test
mvn gatling:test
# Informe HTML en target/gatling/hashtestsimulation-<timestamp>/index.html
```

| Componente | Versión |
|-----------|---------|
| .NET | 10.0 |
| StackExchange.Redis | 3.2.15 |
| Gatling | 3.15.1 |
| gatling-maven-plugin | 4.21.12 |

> **Ref:** RESP Protocol Guide, Ch.1

### 11.9 Verificar todas las aplicaciones

```bash
oc get pods -l 'app in (redis-client-app,spring-cache-app,quarkus-cache-app,spring-session-app,rest-loader-app,eap-session-app)'
oc get routes
```

---

## 12. Validar Casos de Uso

> [!NOTE]
> Este capítulo proporciona **pruebas de humo** para verificar que cada caso de uso funciona tras la configuración. Para el flujo completo de demo con narrativa, inmersión en redis-cli, cambio de backend en vivo, failover HA y auto-reparación GitOps, consulte [DEMO-es.md](DEMO-es.md).

Cada validación a continuación confirma que el caso de uso es funcional. Ejecute estas pruebas después de desplegar el entorno.

### 12.1 Transparencia del Protocolo RESP

Valida que la misma aplicación (Spring Boot + Jedis) y el mismo CLI (`redis-cli`) funcionan contra Redis y Data Grid sin cambios de código.

```bash
ROUTE=$(oc get route redis-client-app -o jsonpath='{.spec.host}')

# Prueba de humo contra el backend actual
curl -sk "https://${ROUTE}/api/info" | jq .
curl -sk -X PUT "https://${ROUTE}/api/strings/test" -H "Content-Type: text/plain" -d "hello"
curl -sk "https://${ROUTE}/api/strings/test" | jq .

# Cambiar backend con parches
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-datagrid.yaml
oc patch deployment redis-client-app -n rhdg-poc --patch-file patches/patch-redis-client-to-redis.yaml
```

**Validación via interfaz web:** Abra `https://${ROUTE}` en un navegador. La interfaz muestra metadatos de conexión (clúster, caché, modo), todas las entradas del caché con TTL, y un log de eventos en vivo alimentado por RESP Pub/Sub + SSE. Utilice el formulario integrado para SET/DEL entradas y observe las actualizaciones en tiempo real.

**Validación cross-app con external-cache-client:** Ejecute el `external-cache-client` localmente contra la Route RESP de Data Grid. Las entradas creadas por `external-cache-client` aparecen en la UI de `redis-client-app` en tiempo real via Pub/Sub, demostrando el uso compartido de caché entre múltiples aplicaciones. El `external-cache-client` también registra en su consola todos los eventos Pub/Sub entrantes.

```bash
# Desde el directorio external-cache-client (se ejecuta localmente, conecta a Data Grid via Route)
cd apps/external-cache-client
mvn quarkus:dev

# En otra terminal — escribir via cliente externo, observar la UI de redis-client-app actualizarse
curl -X PUT http://localhost:8080/api/cache/ext-key -H "Content-Type: text/plain" -d "ext-value"
```

> [!TIP]
> La demo completa de transparencia RESP (incluyendo redis-cli dentro del pod, Fase 1 (Redis) → Fase 2 (Data Grid) → Fase 3 (vuelta a Redis)) está en [DEMO-es.md, Acto 2](DEMO-es.md#acto-2--transparencia-del-protocolo-resp).

### 12.2 Spring Cache

```bash
ROUTE=$(oc get route spring-cache-app -o jsonpath='{.spec.host}')
curl -sk "https://${ROUTE}/api/specialties" | jq .
curl -sk "https://${ROUTE}/api/specialties/CAR" | jq .
curl -sk "https://${ROUTE}/api/specialties/stats" | jq .
```

### 12.3 Quarkus Infinispan Client

```bash
ROUTE=$(oc get route quarkus-cache-app -o jsonpath='{.spec.host}')
curl -sk "https://${ROUTE}/api/specialties" | jq .
curl -sk "https://${ROUTE}/api/specialties/CAR" | jq .
curl -sk "https://${ROUTE}/api/specialties/stats" | jq .
```

Tanto `spring-cache-app` como `quarkus-cache-app` se conectan a la misma caché `app-config` en el clúster `healthcare`, demostrando un consumo agnóstico al runtime.

### 12.4 Correlación de Eventos

Almacenamiento temporal de IDs de correlación para acuse de recibo de entrega de mensajes asíncronos. Ciclo de vida PENDING → DELIVERED con auto-expiración TTL de 5 min.

```bash
ROUTE=$(oc get route quarkus-cache-app -o jsonpath='{.spec.host}')
curl -sk -X POST "https://${ROUTE}/api/events/send?recipient=user-001&payload=appointment-confirmed" | jq .
# Retorna: {"correlationId":"<uuid>", "status":"PENDING"}
# Use el correlationId para verificar, confirmar y validar:
# curl -sk "https://${ROUTE}/api/events/<correlationId>"
# curl -sk -X PUT "https://${ROUTE}/api/events/<correlationId>/ack"
```

Los datos se almacenan en la caché `event-tracking` (distribuida, ASYNC, 2 propietarios, TTL de 5 min) en el clúster `healthcare`.

### 12.5 Spring Session

```bash
ROUTE=$(oc get route spring-session-app -o jsonpath='{.spec.host}')
curl -sk -c /tmp/cookies.txt -X PUT "https://${ROUTE}/api/session/attributes/user" \
  -H "Content-Type: text/plain" -d 'daniel' | jq .
curl -sk -b /tmp/cookies.txt "https://${ROUTE}/api/session" | jq .
```

Los datos de sesión se almacenan en la caché `user-sessions` en el clúster `platform`.

### 12.6 API REST

```bash
ROUTE=$(oc get route rest-loader-app -o jsonpath='{.spec.host}')
curl -sk -X POST "https://${ROUTE}/api/cache/load" | jq .
curl -sk "https://${ROUTE}/api/cache/HOS-001" | jq .
curl -sk "https://${ROUTE}/api/cache/keys"
```

### 12.7 Externalización de Sesiones EAP

```bash
ROUTE=$(oc get route eap-session-app -o jsonpath='{.spec.host}')
curl -sk -c /tmp/eap-cookies.txt -X PUT "https://${ROUTE}/eap-sessions/api/session/attributes/user" \
  -H "Content-Type: text/plain" -d 'daniel' | jq .
curl -sk -b /tmp/eap-cookies.txt "https://${ROUTE}/eap-sessions/api/session" | jq .
DG_ROUTE=$(oc get route platform-external -o jsonpath='{.spec.host}')
curl -sk -u ops-admin:'r3dh4t2026!' "https://${DG_ROUTE}/rest/v2/caches/eap-sessions.war?action=size"
```

### 12.8 Alta Disponibilidad

Las cachés distribuidas con `owners: 2` sobreviven a fallos de pods. Los datos se preservan en el propietario superviviente y se rebalancean automáticamente cuando el pod se recupera.

```bash
# Verificación rápida
oc get pods -l clusterName=healthcare -n rhdg-poc
oc get infinispan healthcare -n rhdg-poc -o jsonpath='Replicas: {.status.replicas}, WellFormed: {.status.conditions[?(@.type=="WellFormed")].status}{"\n"}'
```

> [!TIP]
> La demo completa de failover HA (insertar datos, eliminar un pod, verificar cero pérdida de datos) está en [DEMO-es.md, Acto 6.1](DEMO-es.md#61-alta-disponibilidad--eliminar-un-pod-sin-perder-datos).

> **Ref:** Cache Configuration Guide, Distributed caches (Section 2.1); Operator Guide, Ch.4, Configuring Data Grid clusters

---

## 13. Monitorización

> [!NOTE]
> Este capítulo configura las métricas de Data Grid y un dashboard de Grafana. No instala Grafana. El Grafana Operator y una instancia de Grafana deben estar ya ejecutándose en el clúster (ver [Prerrequisitos](#2-prerrequisitos)). El CR GrafanaDashboard se despliega en el namespace de Grafana (por ejemplo, `shared-monitoring`), no en `rhdg-poc`.

El Operador de Data Grid crea automáticamente CRs `ServiceMonitor` cuando `spec.service.type: DataGrid`. No se requiere configuración manual.

### 13.1 Prerrequisitos

- Monitorización de cargas de trabajo de usuario habilitada en OpenShift
- Prometheus Operator ejecutándose en el clúster
- Grafana Operator instalado con una instancia de Grafana (para el dashboard en la Sección 13.5)

### 13.2 Habilitar monitorización de cargas de trabajo de usuario

```bash
oc apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-monitoring-config
  namespace: openshift-monitoring
data:
  config.yaml: |
    enableUserWorkload: true
EOF
```

### 13.3 Verificar ServiceMonitors

```bash
oc get servicemonitor -n rhdg-poc
```

Salida esperada:
```
NAME                 AGE
healthcare-monitor   ...
platform-monitor     ...
```

### 13.4 Métricas Clave

| Métrica | Descripción |
|--------|-------------|
| `infinispan_cluster_size` | Número de nodos en el clúster |
| `infinispan_statistics_hit_ratio` | Ratio de aciertos de caché |
| `infinispan_statistics_approximate_entries` | Número de entradas en la caché |
| `infinispan_statistics_stores` | Total de operaciones de escritura |
| `infinispan_statistics_retrievals` | Total de operaciones de lectura |
| `infinispan_statistics_average_read_time` | Latencia media de lectura (ms) |
| `infinispan_statistics_average_write_time` | Latencia media de escritura (ms) |
| `infinispan_server_single_port_number_of_connections` | Conexiones de cliente activas |
| `infinispan_lock_manager_number_of_locks_held` | Recuento actual de bloqueos |

Las métricas se exponen en el puerto 11223 a través de un realm de seguridad dedicado.

> **Ref:** Operator Guide, Ch.13, Configuring monitoring

---

## 14. Backup y Restauración

El Operador de Data Grid proporciona CRs `Backup` y `Restore` para crear y restaurar snapshots del clúster. Esto es esencial para recuperación ante desastres, migración entre clústeres y seguridad pre-actualización.

### 14.1 Cómo funciona

1. Se crea un CR `Backup` referenciando un clúster
2. El Operador crea un **pod de capacidad cero** que se une temporalmente al clúster
3. El pod itera todas las entradas de caché y escribe un archivo `.zip` en un PVC
4. El pod termina; el archivo permanece en el PVC
5. Para restaurar, un CR `Restore` lee el archivo y lo aplica a un clúster destino

Ambas operaciones son **eventos únicos**. Se ejecutan una vez y no se reconcilian. El Operador no proporciona aislamiento de snapshot, por lo que desconecte los clientes antes de hacer backup de datos de producción.

> [!WARNING]
> Los CRs de Backup/Restore **no están gestionados por ArgoCD**. Son operaciones imperativas de una sola ejecución, no estado declarativo. Aplíquelos manualmente con `oc apply` cuando sea necesario.

> **Ref:** Operator Guide, Ch.20, Backing up and restoring Data Grid clusters

### 14.2 CR de Backup

El CR de Backup apunta al clúster `platform` (que tiene almacenamiento persistente habilitado):

```yaml
# manifests/43-backup-platform.yaml
apiVersion: infinispan.org/v2alpha1
kind: Backup
metadata:
  name: platform-backup
  namespace: rhdg-poc
spec:
  cluster: platform
  volume:
    storage: 1Gi
```

Cuando se omite `storageClassName`, se utiliza la StorageClass por defecto del clúster. Para especificar una diferente, añada `storageClassName: <su-storage-class>` bajo `volume`.

Cuando no se especifica el campo `spec.resources`, el backup incluye **todo**: todas las cachés, proto schemas, contadores, tareas y plantillas.

Para hacer backup solo de cachés específicas:

```yaml
spec:
  resources:
    caches:
      - user-sessions
      - lookup-data
    protoSchemas:
      - "*"
```

### 14.3 CR de Restore

El CR de Restore referencia el backup por nombre y especifica el clúster destino:

```yaml
# manifests/44-restore-platform.yaml
apiVersion: infinispan.org/v2alpha1
kind: Restore
metadata:
  name: platform-restore
  namespace: rhdg-poc
spec:
  backup: platform-backup
  cluster: platform
```

Si se restaura a una caché existente, los datos se sobrescriben pero la **configuración de la caché se preserva**. Por ejemplo, si la caché origen era distribuida y la caché destino es replicada, los datos se restauran pero la caché permanece replicada.

### 14.4 Guía paso a paso: Ciclo completo de Backup/Restore

**1. Poblar datos en el clúster `platform`**

```bash
# Obtener la ruta
PLATFORM_ROUTE=$(oc get route platform-external -n rhdg-poc -o jsonpath='{.spec.host}')

# Cargar entradas en la caché lookup-data via API REST
for i in $(seq 1 5); do
  curl -k -u ops-admin:'r3dh4t2026!' \
    -X POST "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data/${i}" \
    -H "Content-Type: text/plain" \
    -d "reference-value-${i}"
done

# Verificar una entrada específica
curl -k -u ops-admin:'r3dh4t2026!' \
  "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data/1"
```

**2. Crear el backup**

```bash
oc apply -f manifests/43-backup-platform.yaml
```

**3. Monitorizar el backup**

```bash
# Observar el estado (Initializing → Initialized → Running → Succeeded)
oc get backup platform-backup -n rhdg-poc -w

# Cuando se complete, verificar
oc describe backup platform-backup -n rhdg-poc
```

El pod de backup crea el archivo en `/opt/infinispan/backups/platform-backup.zip` en el PVC y termina.

Mensaje de log esperado:
```
ISPN005044: Backup file created 'platform-backup.zip'
```

**4. Simular pérdida de datos**

```bash
# Limpiar la caché lookup-data (requiere rol admin, método POST)
curl -k -u ops-admin:'r3dh4t2026!' \
  -X POST "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data?action=clear"

# Verificar que está vacía (leer una entrada que ahora debería no existir)
curl -k -u ops-admin:'r3dh4t2026!' \
  "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data/1"
# Esperado: 404 Not Found
```

**5. Restaurar desde backup**

```bash
oc apply -f manifests/44-restore-platform.yaml
```

**6. Monitorizar la restauración**

```bash
oc get restore platform-restore -n rhdg-poc -w
```

Mensaje de log esperado:
```
ISPN005045: Restore 'platform-backup' complete
```

**7. Verificar la recuperación de datos**

```bash
# Leer entradas específicas (deberían haber vuelto)
curl -k -u ops-admin:'r3dh4t2026!' \
  "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data/3"
# Esperado: {"_type":"string","_value":"reference-value-3"}

curl -k -u ops-admin:'r3dh4t2026!' \
  "https://${PLATFORM_ROUTE}/rest/v2/caches/lookup-data/5"
# Esperado: {"_type":"string","_value":"reference-value-5"}
```

### 14.5 Fases de Estado de Backup/Restore

| Fase | Descripción |
|-------|-------------|
| Initializing | El Operador aceptó la solicitud, preparando el pod de capacidad cero |
| Initialized | Recursos del pod creados con éxito |
| Running | El pod está ejecutando el backup/restore en el clúster |
| Succeeded | Operación completada, pod terminado |
| Failed | Operación fallida. Verifique los logs del pod con `oc logs <pod-name>` |

### 14.6 Re-ejecutar un Backup

Los CRs de Backup y Restore son operaciones de una sola ejecución. Para crear un nuevo backup:

```bash
# Eliminar el CR de Backup anterior (los datos del PVC se retienen)
oc delete backup platform-backup -n rhdg-poc

# Re-aplicar: un nuevo pod de capacidad cero ejecuta el backup de nuevo
oc apply -f manifests/43-backup-platform.yaml
```

### 14.7 Resolución de Problemas

Si el estado es `Failed`, examine los logs del pod:

```bash
# El pod permanece tras el fallo, encuéntrelo
oc get pods -n rhdg-poc | grep backup

# Verificar logs
oc logs <backup-pod-name> -n rhdg-poc
```

Causas comunes: almacenamiento PVC insuficiente, clúster no listo, fallos de autenticación.

> **Ref:** Operator Guide, Ch.20, Backing up and restoring Data Grid clusters

---

## 15. Replicación Cross-Site (Opcional)

> [!CAUTION]
> Este capítulo es **solo documentación y referencia**. NO aplique estos manifiestos al clúster. Las instancias de MicroShift no forman parte del alcance de la PoC. Aplicar configuración cross-site a un clúster sin sitios remotos activos causará crasheos de pods (fallo de la pila RELAY2).

La replicación cross-site habilita la recuperación ante desastres replicando datos de caché entre clústeres de Data Grid ejecutándose en diferentes instancias de OpenShift/MicroShift. Data Grid utiliza el protocolo JGroups RELAY2 sobre TCP.

### 15.1 Topología

```
     ocp4.masales.cloud          microshift-a          microshift-b
     (OpenShift SNO)             (MicroShift 4.21)     (MicroShift 4.21)
     Sitio primario              192.168.100.10        192.168.100.11
     ┌──────────────┐           ┌──────────────┐      ┌──────────────┐
     │ platform     │◄─RELAY2──►│ platform     │◄────►│ platform     │
     │ 3 replicas   │  NodePort │ 2 replicas   │      │ 2 replicas   │
     │ Site: ocp4   │  :30900   │ Site: micro-a│      │ Site: micro-b│
     └──────────────┘           └──────────────┘      └──────────────┘
```

### 15.2 Configuración Clave

El CR Infinispan incluye `spec.service.sites` para cross-site:

```yaml
spec:
  service:
    type: DataGrid
    sites:
      local:
        name: ocp4
        expose:
          type: NodePort
          nodePort: 30900
        maxRelayNodes: 1
      locations:
        - name: microshift-a
          url: infinispan+xsite://192.168.100.10:30900
        - name: microshift-b
          url: infinispan+xsite://192.168.100.11:30900
```

Las cachés que necesitan DR incluyen `backups` en su definición:

```yaml
distributedCache:
  backups:
    microshift-a:
      backup:
        strategy: "ASYNC"
        timeout: "10000"
        takeOffline:
          afterFailures: "3"
          minWait: "60000"
```

**Estrategia de backup: ASYNC**, recomendada para Activo/Activo para evitar deadlocks por escrituras concurrentes a la misma clave entre clústeres.

Manifiestos completos y procedimiento de ejecución paso a paso: consulte `docs/cross-site-replication.md`.

> **Ref:** Cross-Site Replication Guide; Operator Guide, Ch.14

---

## 16. Decisiones de Arquitectura

| ID | Decisión | Justificación |
|----|----------|-----------|
| AD-1 | Operador sobre Helm | El Operador proporciona CRDs de Cache, Batch, Backup/Restore para gestión declarativa |
| AD-2 | Múltiples clústeres aislados por tenant | Un Operador, múltiples CRs de Infinispan. Cada uno crea un clúster independiente con su propio StatefulSet, RBAC y configuración |
| AD-3 | TLS con certificados de servicio de OpenShift | `type: Service`, auto-generados y auto-renovados, sin gestión manual de certificados |
| AD-4 | Monitorización via Prometheus | El Operador auto-crea ServiceMonitor; métricas en puerto 11223 |
| AD-5 | Modo de caché por caché, no por clúster | Cachés distribuidas y replicadas coexisten en el mismo clúster |
| AD-6 | RESP para compatibilidad Redis | El endpoint RESP de Data Grid permite que clientes Redis existentes (Jedis) funcionen sin cambios de código |
| AD-7 | Backup ASYNC para cross-site | Evita deadlocks en Activo/Activo; sin impacto de latencia en operaciones del cliente |

---

## 17. Referencia de Cachés

| Caché | Clúster | Modo | Propietarios | TTL | Máx. Entradas | Desalojo | Persistencia |
|-------|---------|------|--------|-----|-------------|----------|-------------|
| `event-tracking` | healthcare | Distribuida, ASYNC | 2 | 5 min | 100.000 | REMOVE | No |
| `app-config` | healthcare | Replicada, SYNC | todos | 10 min | 10.000 | REMOVE | No |
| `user-sessions` | platform | Distribuida, SYNC | 2 | 10 min | 100.000 | REMOVE | No |
| `lookup-data` | platform | Replicada, SYNC | todos | 10 min | 10.000 | REMOVE + pasivación | Sí (fileStore) |
| `eap-sessions` | platform | Distribuida, SYNC | 2 | 10 min | 100.000 | REMOVE | No |
| `respCache` | healthcare | auto-creada | — | — | — | — | No |

La `respCache` es creada automáticamente por Data Grid cuando se utiliza el endpoint RESP. Utiliza codificación `application/octet-stream` y `RESPHashFunctionPartitioner`.

---

## 18. Referencia de Aplicaciones

| Aplicación | Protocolo | Clúster | Caché | Tecnología |
|-------------|----------|---------|-------|------------|
| `redis-client-app` | RESP (puerto 11222) | healthcare | respCache | Spring Boot + Jedis |
| `spring-cache-app` | Hot Rod (puerto 11222) | healthcare | app-config | Spring Boot + @Cacheable |
| `quarkus-cache-app` | Hot Rod (puerto 11222) | healthcare | app-config, event-tracking | Quarkus 3.39 + Infinispan Client |
| `spring-session-app` | Hot Rod (puerto 11222) | platform | user-sessions | Spring Boot + Spring Session |
| `rest-loader-app` | REST (puerto 11222) | platform | lookup-data | ASP.NET Core 10 + HttpClient |
| `eap-session-app` | Hot Rod (puerto 11222) | platform | eap-sessions.war | JBoss EAP 8 (WildFly 34 en la PoC) |
| `dotnet-hash-test` | RESP (puerto 443 vía Route) | hash-test | redis-cache | .NET 10 + StackExchange.Redis (local) |

Todas las aplicaciones se conectan a Data Grid sobre TLS usando el nombre de Service interno del clúster (por ejemplo, `healthcare` o `platform`) en el puerto 11222. `dotnet-hash-test` se conecta externamente a través de una Route passthrough en el puerto 443.

---

## 19. Referencias de Documentación

| Documento | Alcance |
|----------|-------|
| Data Grid Operator Guide | Instalación del Operador, CR de Infinispan, CR de Cache, RBAC, TLS, monitorización, cross-site |
| Data Grid Cross-Site Replication Guide | Transporte RELAY2, estrategias de backup, transferencia de estado, resolución de conflictos |
| Data Grid RESP Protocol Guide | Configuración del endpoint RESP, compatibilidad Redis, autenticación |
| Quarkus Infinispan Client Guide | Integración de Quarkus con Data Grid via Hot Rod |
| Spring Boot Starter Guide | Integración de Spring Cache y Spring Session con Data Grid |
| Data Grid Security Guide | Roles RBAC, mecanismos de autenticación, cifrado TLS |
| Data Grid Cache Configuration Guide | Modos de caché, desalojo, expiración, persistencia, codificación |
| Data Grid Performance and Sizing Guide | Reglas de dimensionamiento, metodología de benchmark, comparación de modos de caché |
| Data Grid REST API Guide | Operaciones del endpoint REST, gestión de cachés, operaciones cross-site |
| Data Grid Server Guide | Configuración del servidor, realms de seguridad, fuentes de datos |

Toda la documentación está disponible en el [Portal de Clientes de Red Hat, Data Grid 8.6](https://access.redhat.com/documentation/en-us/red_hat_data_grid/8.6).

---

## 20. Índice de Manifiestos

| Archivo | Recurso | Descripción |
|------|----------|-------------|
| `01-operator-group.yaml` | OperatorGroup | Limitar el alcance del Operador al namespace |
| `02-operator-subscription.yaml` | Subscription | Instalar el Operador de Data Grid |
| `03-healthcare-credentials.yaml` | Secret | Usuarios/roles para el clúster healthcare |
| `04-platform-credentials.yaml` | Secret | Usuarios/roles para el clúster platform |
| `05-cluster-healthcare.yaml` | Infinispan | Clúster healthcare (2 réplicas, RESP habilitado) |
| `06-cluster-platform.yaml` | Infinispan | Clúster platform (3 réplicas, almacenamiento persistente) |
| `07-cache-event-tracking.yaml` | Cache | Distribuida, TTL 5 min |
| `08-cache-app-config.yaml` | Cache | Replicada, TTL 10 min |
| `09-cache-user-sessions.yaml` | Cache | Distribuida, TTL 10 min |
| `10-cache-lookup-data.yaml` | Cache | Replicada, TTL 10 min, fileStore persistente |
| `11-cache-eap-sessions.yaml` | Cache | Distribuida, TTL 10 min |
| `12-redis-secret.yaml` | Secret | Contraseña de Redis |
| `13-redis-deployment.yaml` | Deployment | Instancia Redis de referencia |
| `14-redis-service.yaml` | Service | Redis ClusterIP |
| `15-redis-client-app-deployment.yaml` | Deployment | App de prueba RESP (despliegue manual, no gestionada por ArgoCD) |
| `16-redis-client-app-service.yaml` | Service | ClusterIP (despliegue manual) |
| `17-redis-client-app-route.yaml` | Route | TLS edge (despliegue manual) |
| `18-spring-cache-app-deployment.yaml` | Deployment | App de prueba Spring Cache |
| `19-spring-cache-app-service.yaml` | Service | ClusterIP |
| `20-spring-cache-app-route.yaml` | Route | TLS edge |
| `21-spring-session-app-deployment.yaml` | Deployment | App de prueba Spring Session |
| `22-spring-session-app-service.yaml` | Service | ClusterIP |
| `23-spring-session-app-route.yaml` | Route | TLS edge |
| `24-rest-loader-app-deployment.yaml` | Deployment | App de prueba REST loader |
| `25-rest-loader-app-service.yaml` | Service | ClusterIP |
| `26-rest-loader-app-route.yaml` | Route | TLS edge |
| `27-eap-session-app-deployment.yaml` | Deployment | App de prueba sesiones JBoss EAP |
| `27a-service-ca-bundle.yaml` | ConfigMap | Bundle CA de OpenShift para confianza TLS |
| `28-eap-session-app-service.yaml` | Service | ClusterIP |
| `29-eap-session-app-route.yaml` | Route | TLS edge |
| `30-grafana-dashboard-datagrid.yaml` | GrafanaDashboard | Dashboard de métricas de Data Grid para Grafana |
| `31-gitea-instance.yaml` | Gitea | Servidor Git en el clúster (interno, no visible al cliente) |
| `31a-gitea-route-alias.yaml` | Route | Alias URL corto para Gitea (interno, no visible al cliente) |
| `32-argocd-application.yaml` | Application | CR de Application de ArgoCD (interno, no visible al cliente) |
| `33-xsite-cluster-ocp4.yaml` | Infinispan | Cross-site: sitio primario |
| `34-xsite-cluster-microshift-a.yaml` | Infinispan | Cross-site: sitio DR-a |
| `35-xsite-cluster-microshift-b.yaml` | Infinispan | Cross-site: sitio DR-b |
| `36-xsite-cache-user-sessions.yaml` | Cache | Cross-site: configuración de backup (ocp4) |
| `37-xsite-cache-user-sessions-microshift-a.yaml` | Cache | Cross-site: configuración de backup (site-a) |
| `38-xsite-cache-user-sessions-microshift-b.yaml` | Cache | Cross-site: configuración de backup (site-b) |
| `40-quarkus-cache-app-deployment.yaml` | Deployment | App de prueba Quarkus Infinispan Client |
| `41-quarkus-cache-app-service.yaml` | Service | ClusterIP |
| `42-quarkus-cache-app-route.yaml` | Route | TLS edge |
| `43-backup-platform.yaml` | Backup | Backup completo del clúster platform |
| `44-restore-platform.yaml` | Restore | Restaurar clúster platform desde backup |
| `50-hashtest-credentials.yaml` | Secret | Usuarios/roles para clúster hash-test |
| `51-cluster-hash-test.yaml` | Infinispan | Clúster hash-test (1 réplica, RESP + fileStore, Route externa) |
| `52-hashtest-server-config.yaml` | ConfigMap | Configuración del servidor con redis-cache, CRC16, conector RESP |

---

## 21. Matriz de Validación

Esta matriz resume cada capacidad validada durante la PoC. Sirve como referencia de aceptación en ausencia de criterios formales del cliente.

### 21.1 Modelos de Consumo

| # | Capacidad | Cómo se Validó | App / Caché | Resultado | Evidencia |
|---|-----------|---------------|-------------|--------|----------|
| C-1 | Spring Cache (`@Cacheable`) | App Spring Boot cachea especialidades médicas via Hot Rod | `spring-cache-app` → `app-config` | Validado | [Sección 12.2](#122-spring-cache) |
| C-2 | Quarkus Infinispan Client | App Quarkus cachea los mismos datos via inyección `@Remote` | `quarkus-cache-app` → `app-config` | Validado | [Sección 12.3](#123-quarkus-infinispan-client) |
| C-3 | Spring Session | Sesión HTTP externalizada a Data Grid via Hot Rod | `spring-session-app` → `user-sessions` | Validado | [Sección 12.5](#125-spring-session) |
| C-4 | API REST | App ASP.NET Core 10 carga/lee datos de referencia via endpoint REST de Data Grid, demuestra acceso agnóstico al lenguaje | `rest-loader-app` → `lookup-data` | Validado | [Sección 12.6](#126-api-rest) |
| C-5 | Protocolo RESP (compatibilidad Redis) | Misma app Spring Boot + Jedis funciona contra Redis y luego Data Grid, cero cambios de código. Interfaz web con monitoreo en tiempo real via Pub/Sub + SSE | `redis-client-app` → `respCache` | Validado | [Sección 12.1](#121-transparencia-del-protocolo-resp) |
| C-6 | RESP Pub/Sub (cross-app) | `redis-client-app` y `external-cache-client` comparten eventos via canal RESP PUBLISH/SUBSCRIBE `cache-events`. Escrituras remotas aparecen en la interfaz web en tiempo real | `redis-client-app` + `external-cache-client` → `respCache` | Validado | [Sección 12.1](#121-transparencia-del-protocolo-resp) |
| C-7 | Externalización de Sesiones JBoss EAP 8 | Sesiones HTTP de JBoss EAP 8 externalizadas a Data Grid via Hot Rod | `eap-session-app` → `eap-sessions` | Validado | [Sección 12.7](#127-externalización-de-sesiones-eap) |
| C-8 | Correlación de Eventos | Flujo de correlación de mensajes asíncronos: PENDING → DELIVERED con expiración TTL | `quarkus-cache-app` → `event-tracking` | Validado | [Sección 12.4](#124-correlación-de-eventos) |
| C-9 | RESP con .NET + Redis Hashes | App .NET 10 usando StackExchange.Redis con HGET/HSET, patrón cache-aside con fallback a SQLite, protocolo RESP3 | `dotnet-hash-test` → `redis-cache` | Validado | [Sección 11.8](#118-dotnet-hash-test-resp--redis-hashes--pruebas-de-carga) |
| C-10 | Pruebas de carga (Gatling) | Prueba de carga HTTP con 3 escenarios (HGET individual, multi-HGET, mixto), pico 50 req/s, 3.420 peticiones, 0 fallos | `gatling-load-test` → `dotnet-hash-test` | Validado | [Sección 11.8](#118-dotnet-hash-test-resp--redis-hashes--pruebas-de-carga) |

### 21.2 Multi-Tenancy y Aislamiento

| # | Capacidad | Cómo se Validó | Resultado | Evidencia |
|---|-----------|---------------|--------|----------|
| T-1 | Múltiples clústeres aislados | Dos CRs de Infinispan independientes (`healthcare`, `platform`), cada uno con su propio StatefulSet, pods y configuración | Validado | [Capítulo 7](#7-desplegar-clústeres-de-data-grid) |
| T-2 | Credenciales por clúster | Cada clúster tiene su propio Secret con usuarios y roles independientes, sin acceso cruzado posible | Validado | [Capítulo 6](#6-crear-credenciales) |
| T-3 | Roles RBAC | Rol `application` para apps (leer/escribir datos), rol `admin` para operaciones (gestión de cachés, backup, limpieza) | Validado | [Sección 6.4](#64-roles-rbac) |
| T-4 | Segregación a nivel de caché | Cada app se conecta solo a su caché designada dentro de su clúster, forzado por credenciales y aislamiento de clúster | Validado | [Capítulo 18](#18-referencia-de-aplicaciones) |

### 21.3 Operaciones

| # | Capacidad | Cómo se Validó | Resultado | Evidencia |
|---|-----------|---------------|--------|----------|
| O-1 | Alta Disponibilidad: Failover | Pod eliminado con `oc delete pod`, datos preservados via segundo propietario, StatefulSet se recupera automáticamente, cero pérdida de datos | Validado | [Sección 12.8](#128-alta-disponibilidad--demo-de-failover) |
| O-2 | Backup | CR de Backup crea pod de capacidad cero, archiva todas las cachés en PVC `.zip`, estado transiciona a `Succeeded` | Validado | [Sección 14.2](#142-cr-de-backup) |
| O-3 | Restore | CR de Restore lee archivo del PVC, restaura todas las entradas al clúster, datos verificados tras pérdida simulada | Validado | [Sección 14.4](#144-guía-paso-a-paso--ciclo-completo-de-backuprestore) |
| O-4 | Monitorización | Operador auto-crea ServiceMonitor, Prometheus recopila métricas en puerto 11223, dashboard de Grafana desplegado | Validado | [Capítulo 13](#13-monitorización) |
| O-5 | GitOps (Cache as a Service) | Todos los recursos gestionados declarativamente via Git + ArgoCD; commit dispara sync, desviaciones se auto-reparan | Validado | [docs/gitops-workflow.md](docs/gitops-workflow.md) |
| O-6 | Gestión declarativa de cachés | Cachés definidas como CRs `Cache` en YAML, aplicadas por el Operador, sin CLI imperativo necesario | Validado | [Capítulo 8](#8-crear-cachés) |
| O-7 | Ciclo de vida del Operador | Operador de Data Grid instalado via CR de Subscription, gestiona ciclo de vida completo del clúster | Validado | [Capítulo 4](#4-instalar-el-operador-de-data-grid) |

### 21.4 Seguridad

| # | Capacidad | Cómo se Validó | Resultado | Evidencia |
|---|-----------|---------------|--------|----------|
| S-1 | Cifrado TLS (automático) | `endpointEncryption.type: Service`, OpenShift genera y auto-renueva certificados | Validado | [Capítulo 7](#7-desplegar-clústeres-de-data-grid) |
| S-2 | Autenticación | Todos los endpoints requieren usuario/contraseña; acceso no autenticado rechazado | Validado | [Sección 12.1](#121-transparencia-del-protocolo-resp) |
| S-3 | Aislamiento de credenciales | Cada clúster tiene Secret independiente; comprometer las credenciales de un clúster no expone al otro | Validado | [Capítulo 6](#6-crear-credenciales) |

### 21.5 Capacidades Avanzadas

| # | Capacidad | Cómo se Validó | Resultado | Evidencia |
|---|-----------|---------------|--------|----------|
| A-1 | Replicación Cross-Site (DR) | Datos de caché replicados entre OpenShift SNO e instancias MicroShift via JGroups RELAY2 | Validado | [Capítulo 15](#15-replicación-cross-site-opcional) |
| A-2 | Modo caché distribuida | `event-tracking`, `user-sessions`, `eap-sessions`: datos particionados entre propietarios, escalabilidad horizontal | Validado | [Capítulo 17](#17-referencia-de-cachés) |
| A-3 | Modo caché replicada | `app-config`, `lookup-data`: copia completa en cada nodo, lecturas locales rápidas para datos de referencia | Validado | [Capítulo 17](#17-referencia-de-cachés) |
| A-4 | Almacenamiento persistente | `lookup-data` utiliza `fileStore` para datos duraderos que sobreviven a reinicios completos del clúster | Validado | [Sección 8.4](#84-lookup-data-platform) |
| A-5 | Expiración basada en TTL | `event-tracking` (5 min), todas las demás (10 min); entradas auto-expiran, ninguna caché sin TTL | Validado | [Capítulo 17](#17-referencia-de-cachés) |
| A-6 | Múltiples runtimes, mismo clúster | Apps Spring Boot, Quarkus y ASP.NET Core 10 consumen el mismo clúster de Data Grid via diferentes protocolos (Hot Rod, REST) | Validado | [Sección 12.2](#122-spring-cache), [Sección 12.3](#123-quarkus-infinispan-client), [Sección 12.6](#126-api-rest) |
| A-7 | Modo de replicación ASYNC | `event-tracking` utiliza ASYNC: escrituras retornan tras propietario primario, replicación en segundo plano; menor latencia para datos efímeros | Validado | [Sección 8.1](#81-event-tracking-healthcare), [Sección 8.8](#88-replicación-sync-vs-async) |
| A-8 | Pasivación (jerarquía memoria/disco) | `lookup-data` utiliza pasivación: entradas desalojadas escritas en disco en lugar de descartadas, reactivadas al acceder | Validado | [Sección 8.4](#84-lookup-data-platform), [Sección 8.9](#89-estrategias-de-desalojo-y-persistencia) |

### 21.6 Resumen

| Categoría | Total | Validado | Parcial | No Probado |
|----------|-------|-----------|---------|------------|
| Modelos de Consumo | 10 | 10 | 0 | 0 |
| Multi-Tenancy y Aislamiento | 4 | 4 | 0 | 0 |
| Operaciones | 7 | 7 | 0 | 0 |
| Seguridad | 3 | 3 | 0 | 0 |
| Capacidades Avanzadas | 8 | 8 | 0 | 0 |
| **Total** | **32** | **32** | **0** | **0** |
