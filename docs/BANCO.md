# Banco de dados

MariaDB ou MySQL. A conexão fica em `plugins/GearScore/database.yml`.

## Migrações

Os scripts ficam em `src/main/resources/db/migration` e são aplicados em ordem
na primeira conexão bem-sucedida. As versões aplicadas são registradas em
`<prefixo>schema_version`, então cada script roda uma única vez. Se o banco
estiver fora quando o servidor iniciar, a migração acontece assim que ele
voltar.

| Versão | Script | Conteúdo |
|---|---|---|
| 1 | `V1__create_players.sql` | tabela de jogadores |
| 2 | `V2__add_indexes.sql` | índices do ranking e da busca por nome |

Para evoluir o esquema, crie `V3__descricao.sql` e registre-o na lista de
`Migrator`. `${prefix}` é substituído por `table-prefix`.

## Esquema

Com o prefixo padrão `gearscore_`:

```sql
CREATE TABLE gearscore_players (
    uuid          CHAR(36)    NOT NULL,            -- UUID do jogador
    last_name     VARCHAR(32) NOT NULL,            -- último nome conhecido
    score         INT         NOT NULL DEFAULT 0,  -- GearScore atual
    armor_score   INT         NOT NULL DEFAULT 0,  -- parcela de armadura
    weapon_score  INT         NOT NULL DEFAULT 0,  -- parcela de arma
    tier          VARCHAR(32) NOT NULL DEFAULT '', -- id da faixa
    source_server VARCHAR(32) NOT NULL DEFAULT '', -- servidor que gravou
    rules_version INT         NOT NULL DEFAULT 0,  -- versão das regras usadas
    updated_at    BIGINT      NOT NULL DEFAULT 0,  -- epoch em milissegundos (UTC)
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX gearscore_idx_score ON gearscore_players (score DESC, updated_at);
CREATE INDEX gearscore_idx_name  ON gearscore_players (last_name);

CREATE TABLE gearscore_schema_version (
    version     INT          NOT NULL,
    description VARCHAR(100) NOT NULL,
    applied_at  BIGINT       NOT NULL,
    PRIMARY KEY (version)
);
```

Uma linha por jogador. Ela representa o **último equipamento avaliado**; não há
histórico nem recorde.

`updated_at` é o momento da avaliação que produziu o resultado gravado. Para
ler como data:

```sql
SELECT last_name, score, tier, source_server, FROM_UNIXTIME(updated_at / 1000) AS atualizado
FROM gearscore_players ORDER BY score DESC LIMIT 10;
```

## Consultas feitas pelo plugin

Todas usam prepared statements e rodam fora da thread principal.

| Quando | Consulta | Índice |
|---|---|---|
| jogador entra | `SELECT ... WHERE uuid = ?` | chave primária |
| pontuação mudou (servidor oficial) | `INSERT ... ON DUPLICATE KEY UPDATE` em lote | chave primária |
| `/gs ver` de jogador offline | `SELECT ... WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1` | `idx_name` |
| `/gs top`, no máximo uma vez por janela de cache | `SELECT ... ORDER BY score DESC, updated_at ASC LIMIT ?` | `idx_score` |
| `%gearscore_position%` de quem está fora do topo, no máximo uma vez por janela de cache por jogador | `SELECT COUNT(*) WHERE score > ?` | `idx_score` |

A tabela inteira nunca é carregada na memória.

## Threads e conexões

| Recurso | Padrão | Configuração |
|---|---|---|
| Conexões no pool | 3 (mínimo ocioso 1) | `pool.max-connections`, `pool.min-idle` |
| Thread de gravação | 1 | fixo |
| Threads de leitura | 1 | `reads.threads` |
| Leituras na fila | 64 | `reads.max-queued` |

Nenhuma thread é criada por jogador ou por evento.

## Falhas

* Se o banco cair, as gravações ficam pendentes na memória, **uma por
  jogador** (o estado mais recente substitui o anterior), até
  `writes.max-pending`.
* As tentativas seguem espera progressiva (`retry.initial-delay-ms` dobrando
  até `retry.max-delay-ms`). Durante a espera, leituras falham na hora em vez
  de aguardar o timeout de conexão.
* O log registra a falha no máximo uma vez por minuto, com a contagem de
  mensagens suprimidas, e avisa quando a conexão volta.
* Jogadores online continuam consultáveis pelo cache (`/gs`, `/gs detalhes`,
  placeholders). Ranking e consulta de offline respondem que o banco está
  indisponível; o ranking continua servindo o último resultado lido, se houver.
* No desligamento, o plugin tenta gravar o que está pendente por até
  `shutdown-timeout-seconds` e nunca bloqueia além disso. O que não foi gravado
  é recalculado na próxima vez que o jogador entrar em um servidor oficial.
