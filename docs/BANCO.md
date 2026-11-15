# Banco de dados

MariaDB (ou MySQL). A conexão fica em `plugins/GearScore/database.yml`.

O driver **MariaDB Connector/J** vem embutido no JAR do plugin e é o único
usado. Não há opção de driver para configurar nem dependência a instalar.

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
| 3 | `V3__stable_ranking_order.sql` | coluna gerada `rank_score`, índice `idx_rank`, remoção de `idx_score` |

Para evoluir o esquema, crie `V4__descricao.sql` e registre-o na lista de
`Migrator`. `${prefix}` é substituído por `table-prefix`.

A migração 3 foi executada em MariaDB 10.4.32 sobre o esquema da versão 1.0.0
com dados: a linha existente foi preservada e já entrou na ordem do ranking.

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
    rank_score    INT GENERATED ALWAYS AS (-score), -- pontuação negada (gerada)
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX gearscore_idx_rank ON gearscore_players (rank_score, updated_at, uuid);
CREATE INDEX gearscore_idx_name ON gearscore_players (last_name);

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
FROM gearscore_players ORDER BY rank_score, updated_at, uuid LIMIT 10;
```

## Ordem do ranking

Existe uma única política, usada pelo `/gs top`, pelo cache do ranking, pela
posição individual e por `%gearscore_position%`:

1. maior pontuação;
2. em caso de empate, quem atingiu a pontuação primeiro (`updated_at` menor);
3. por fim, o UUID (texto, crescente).

Dois jogadores nunca ocupam a mesma posição, e a posição individual é sempre o
número da linha do jogador nessa ordem.

### Por que `rank_score`

A ordem mistura sentidos (pontuação decrescente, data crescente). Índices com
partes decrescentes só existem a partir do MariaDB 10.8; antes disso o `DESC`
do índice é ignorado. No MariaDB 10.4.32, com 200 mil linhas, a consulta
original `ORDER BY score DESC, updated_at ASC` ordenava a tabela inteira a cada
leitura (`Using filesort` sobre ~199 mil linhas).

`rank_score` é a pontuação negada. Com ela, toda a ordem é crescente e um
índice comum resolve em qualquer versão. A coluna é **gerada**: o banco a
calcula a partir de `score`, então ela não pode ficar inconsistente e o plugin
não precisa gravá-la.

### Posição individual

Calculada em uma leitura agrupada, para qualquer quantidade de jogadores:

```
posição = 1 + linhas com pontuação melhor + linhas à frente dentro da mesma pontuação
```

* **linhas à frente dentro da mesma pontuação:** um trecho curto do índice por
  jogador;
* **linhas com pontuação melhor:** uma passada só de índice que conta linhas
  por pontuação (`GROUP BY rank_score`); a soma acumulada é feita no plugin.

As duas instruções rodam na mesma transação de leitura.

## Consultas feitas pelo plugin

Todas usam prepared statements e rodam fora da thread principal.

| Quando | Consulta | Acesso |
|---|---|---|
| jogador entra em servidor oficial | `SELECT ... WHERE uuid = ?` | chave primária |
| pontuação mudou (servidor oficial) | `INSERT ... ON DUPLICATE KEY UPDATE` em lote | chave primária |
| jogadores online em servidor de consulta | `SELECT ... WHERE uuid IN (...)`, até `query-refresh.batch-size` por consulta | chave primária |
| `/gs ver` de jogador offline | `SELECT ... WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1` | `idx_name` |
| `/gs top`, no máximo uma vez por `ranking.cache-seconds` | `SELECT ... ORDER BY rank_score, updated_at, uuid LIMIT ?` | `idx_rank`, em ordem, só as linhas pedidas |
| posições, no máximo uma vez por `ranking.position-refresh-seconds` e só com o placeholder em uso | as duas instruções da seção anterior | `idx_rank` |

A tabela inteira nunca é carregada na memória.

### Planos e tempos medidos em MariaDB real

MariaDB **10.4.32** local, tabela com **200 000 linhas**, cerca de 1500
pontuações distintas (muitos empates), uma conexão, mediana de 5 execuções;
onde há um intervalo, são os valores de duas rodadas.
Rede local e disco da máquina de desenvolvimento: os tempos servem como ordem
de grandeza, não como previsão do servidor de produção.

| Consulta | Plano (`EXPLAIN`) | Tempo |
|---|---|---:|
| Top 100 | `type=index key=idx_rank rows=100`, `Using index`, sem filesort | 0,72 ms |
| 100 jogadores por UUID | `type=range key=PRIMARY` | 0,80 ms |
| 1 jogador por UUID | chave primária | 0,21 ms |
| Posições de 200 jogadores | linhas por pontuação: `type=index key=idx_rank`, `Using index`; à frente no empate: `type=ref key=idx_rank rows≈61` | 43–59 ms |

A leitura de posições percorre o índice inteiro uma vez (é isso que custa os
~43–59 ms), então o tempo cresce com o tamanho da tabela e quase não cresce com o
número de jogadores online. Alternativas medidas na mesma base e descartadas:
`ROW_NUMBER()` em subconsulta, 1,4 s; contagem correlacionada por jogador,
12 s.

Nas 200 mil linhas, as posições devolvidas conferiram com a ordem da lista nas
300 primeiras linhas (com empates) e com uma contagem direta para 20 jogadores
sorteados do restante da tabela.

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
  `writes.max-pending`. O limite vale também quando um lote que falhou volta
  para a fila: ele entra antes do que chegou depois, nunca substitui um estado
  mais novo do mesmo jogador e, se faltar espaço, o que sai é o mais antigo.
* As tentativas seguem espera progressiva (`retry.initial-delay-ms` dobrando
  até `retry.max-delay-ms`). Durante a espera, leituras falham na hora em vez
  de aguardar o timeout de conexão, e as atualizações periódicas não são
  enviadas.
* O log registra a falha no máximo uma vez por minuto, com a contagem de
  mensagens suprimidas, e avisa quando a conexão volta.
* Jogadores online continuam consultáveis pelo cache (`/gs`, `/gs detalhes`,
  placeholders). Ranking e consulta de offline respondem que o banco está
  indisponível; o ranking continua servindo o último resultado lido, se houver.
* Quando o banco volta, gravações, atualizações dos servidores de consulta e
  posições são retomadas sozinhas.
* No desligamento, o plugin tenta gravar o que está pendente por até
  `shutdown-timeout-seconds` e nunca bloqueia além disso. O que não foi gravado
  é recalculado na próxima vez que o jogador entrar em um servidor oficial.

## Compatibilidade

* Testado em MariaDB 10.4.32. Colunas geradas indexadas exigem MariaDB 10.2.3+
  ou MySQL 5.7+.
* MySQL e versões mais novas do MariaDB não foram testados.
* O método de autenticação PARSEC (MariaDB 11.6+) não é suportado pelo driver
  embutido. Os testes usaram `mysql_native_password`; outros métodos não foram testados.
