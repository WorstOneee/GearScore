# Instalação

## Requisitos

* Paper 1.21.11 (ou fork compatível)
* Java 21
* MariaDB 10.5+ ou MySQL 8+ (opcional, mas necessário para ranking e para
  compartilhar a pontuação entre servidores)
* PlaceholderAPI (opcional)

O plugin roda **somente nos servidores Paper**. Não instale nada no Velocity:
o proxy não avalia equipamentos.

## 1. Banco de dados

Crie um usuário com acesso ao banco usado pela rede:

```sql
CREATE DATABASE IF NOT EXISTS serpens CHARACTER SET utf8mb4;
CREATE USER 'gearscore'@'%' IDENTIFIED BY 'uma-senha-forte';
GRANT SELECT, INSERT, UPDATE, CREATE, INDEX, ALTER ON serpens.* TO 'gearscore'@'%';
FLUSH PRIVILEGES;
```

Não é preciso criar tabelas: as migrações são aplicadas automaticamente na
primeira conexão. Veja o esquema em [BANCO.md](BANCO.md).

## 2. Instalar o JAR

1. Copie `GearScore-1.0.0.jar` para a pasta `plugins/` de **cada** servidor
   Paper que deve mostrar a pontuação (New Era, hub, eventos...).
2. Inicie o servidor uma vez para gerar `plugins/GearScore/`.
3. Edite `database.yml` (mesmos dados em todos os servidores) e `config.yml`.
4. Reinicie.

## 3. Definir quem calcula

Em `config.yml` de cada servidor:

```yaml
server:
  name: "newera"          # nome DESTA instância
  official-sources:
    - "newera"            # quem pode calcular e gravar a pontuação oficial
```

| Servidor | `server.name` | Está em `official-sources`? | Comportamento |
|---|---|---|---|
| New Era | `newera` | sim | lê os equipamentos, calcula e grava |
| Hub | `hub` | não | não lê inventários, não grava; só consulta o banco |
| Eventos | `eventos` | não | idem |

Use a **mesma lista** `official-sources` em todos os servidores e mude apenas
`server.name`. Assim os kits temporários da hub e dos eventos nunca
sobrescrevem a pontuação do New Era: nessas instâncias o plugin nem registra os
listeners de equipamento nem cria a fila de gravação.

Na inicialização o console confirma o modo:

```
[GearScore] Server "newera" is an official source: equipment is evaluated and persisted.
[GearScore] Server "hub" is not an official source: scores are only read from the database.
```

## 4. Comandos

| Comando | Descrição | Permissão | Padrão |
|---|---|---|---|
| `/gearscore` ou `/gs` | sua pontuação | `gearscore.use` | todos |
| `/gs ver <jogador>` | pontuação de outro jogador (online ou offline) | `gearscore.view.others` | todos |
| `/gs detalhes` | contribuição de cada peça e encantamento | `gearscore.details` | todos |
| `/gs detalhes <jogador>` | detalhamento de outro jogador online | `gearscore.details.others` | OP |
| `/gs top [página]` | ranking paginado | `gearscore.top` | todos |
| `/gs reload` | recarrega `config.yml` e `messages.yml` | `gearscore.admin.reload` | OP |
| `/gs diag` | contadores de desempenho | `gearscore.admin.diag` | OP |

`gearscore.admin` concede as três permissões administrativas.

Observações:

* O detalhamento existe apenas para jogadores online em um servidor de origem
  oficial (é lá que os itens são lidos). Na hub, `/gs` mostra a pontuação
  oficial com o servidor e a data da atualização.
* O ranking mostra o **último equipamento avaliado** de cada jogador, com a
  data. Não é recorde histórico: trocar para um equipamento pior baixa a
  pontuação.
* `/gs reload` não recria a conexão com o banco nem muda `server.name`,
  `official-sources` e `ranking`; para isso, reinicie.

## 5. PlaceholderAPI

Com o PlaceholderAPI instalado, a expansão é registrada sozinha.

| Placeholder | Valor |
|---|---|
| `%gearscore_total%` | pontuação total |
| `%gearscore_armor%` | pontuação de armadura |
| `%gearscore_weapon%` | pontuação de arma |
| `%gearscore_tier%` | nome da faixa, sem cores |
| `%gearscore_tier_colored%` | nome da faixa com cores (códigos `§`) |
| `%gearscore_tier_id%` | id da faixa no `config.yml` |
| `%gearscore_position%` | posição no ranking |

Enquanto o dado não estiver disponível, retorna `placeholders.fallback`
(padrão `-`).

Os placeholders leem apenas a memória. O plugin **fornece** os valores; quem
decide quando redesenhar é o TAB, o plugin de hologramas ou o scoreboard. O
GearScore não envia pacotes nem atualiza essas interfaces por conta própria.

Exemplos:

```yaml
# TAB (config.yml)
tabsuffix: " &8[&6%gearscore_total%&8]"

# DecentHolograms
- "&6GearScore: &f%gearscore_total% &7(%gearscore_tier%)"
```

Para hologramas de ranking, use o intervalo de atualização do próprio plugin
de hologramas com folga (30 s ou mais): `%gearscore_position%` é recalculado no
máximo a cada `ranking.position-cache-seconds`.

## 6. Verificar

1. Entre no New Era, equipe uma armadura e rode `/gs` e `/gs detalhes`.
2. Troque de arma na hotbar e rode `/gs` de novo: a arma considerada muda.
3. Rode `/gs diag`: "Banco: disponível" e "linhas gravadas" maior que zero.
4. Vá para a hub e rode `/gs`: a pontuação é a mesma, com "em newera".
5. Na hub, equipe qualquer item e volte ao New Era: nada foi sobrescrito.

## Compilar

```bash
./mvnw clean package
```

O JAR sai em `target/GearScore-1.0.0.jar`. Para rodar o teste de carga
simulado:

```bash
./mvnw test -Pbench
```

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| `Database error, retrying in ...` no console | host, porta, usuário ou senha em `database.yml`; o plugin continua funcionando em memória e reconecta sozinho |
| `/gs` responde "ainda não está disponível" na hub | o jogador nunca foi avaliado em um servidor oficial, ou o banco está fora |
| Um encantamento não pontua | não há regra para ele naquele tipo de equipamento; veja `/gs diag` |
| Pontuação não muda em um mundo | o mundo está em `evaluation.ignored-worlds` |
| Item de material novo ignorado | o console lista, ao iniciar, os itens do `config.yml` que não existem na versão |
