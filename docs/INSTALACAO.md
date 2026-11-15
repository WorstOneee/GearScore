# Instalação

## Requisitos

* Paper 1.21.11 (ou fork compatível)
* Java 21
* MariaDB 10.2.3 ou mais novo (opcional, mas necessário para ranking e para
  compartilhar a pontuação entre servidores). Testado em MariaDB 10.4.32.
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
primeira conexão (o usuário precisa de `CREATE`, `INDEX` e `ALTER` para
isso). Veja o esquema em [BANCO.md](BANCO.md).

O driver MariaDB vem embutido no JAR; não há nada a instalar.

## 2. Instalar o JAR

1. Copie `GearScore-3.0.1.jar` para a pasta `plugins/` de **cada** servidor
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

### Como a hub e os eventos ficam atualizados

Um servidor de consulta busca no banco a pontuação oficial dos jogadores que
estão online nele:

1. ao entrar;
2. de novo `query-refresh.join-recheck-seconds` depois (padrão 5 s). Quando o
   jogador troca de servidor pelo Velocity, o destino costuma ler o banco
   antes de o New Era gravar o último resultado; esta segunda leitura pega a
   gravação. Mantenha o valor maior que `writes.flush-interval-ms` do New Era
   (padrão 3 s);
3. a cada `query-refresh.interval-seconds` (padrão 30 s) enquanto o jogador
   estiver online. Isso também corrige leituras que falharam e jogadores que
   ainda não tinham registro.

As leituras são agrupadas por chave primária (até `batch-size` jogadores por
consulta, até `max-batches-per-cycle` consultas por segundo) e alinhadas no
tempo, de modo que jogadores que entraram em momentos diferentes vão na mesma
consulta. Nenhum inventário é lido nesses servidores.

Uma resposta atrasada nunca substitui um dado mais novo nem recoloca no cache
um jogador que já saiu ou reconectou.

Não é necessário Redis, mensageria nem plugin no proxy: o banco que já guarda a
pontuação é a única ligação entre os servidores.

Na inicialização o console confirma o modo:

```
[GearScore] Server "newera" is an official source: equipment is evaluated and persisted.
[GearScore] Server "hub" is not an official source: scores are only read from the database.
```

## 4. Comandos

| Comando | Descrição | Permissão | Padrão |
|---|---|---|---|
| `/gearscore` ou `/gs` | sua pontuação | `gearscore.use` | todos |
| `/gs <jogador>` | pontuação de outro jogador, online ou offline (`/gs ver <jogador>` também funciona) | `gearscore.view.others` | todos |
| `/gs detalhes` | as cinco peças escolhidas e seus pontos | `gearscore.details` | todos |
| `/gs detalhes <jogador>` | detalhamento de outro jogador online | `gearscore.details.others` | OP |
| `/gs top [página]` | ranking paginado | `gearscore.top` | todos |
| `/gs reload` | recarrega `config.yml` e `messages.yml` | `gearscore.admin.reload` | OP |
| `/gs diag` | diagnóstico técnico | `gearscore.admin.diag` | OP |

`gearscore.admin` concede as três permissões administrativas.

### O que cada comando mostra

`/gs` e `/gs <jogador>` respondem com uma linha: jogador, pontuação e
classificação.

```
GearScore » zWorstOne • 1125 GS • Elite
```

`/gs detalhes [jogador]` mostra as cinco peças que formam a pontuação —
capacete, peitoral, calça, bota e arma — cada uma com o total, a base e os
encantamentos (passe o mouse sobre a linha para ver cada encantamento), e
quando a pontuação foi atualizada. O detalhamento existe apenas para jogadores
online no servidor de origem oficial, que é onde os itens são lidos.

O nome do servidor de origem e a versão das regras **não** aparecem para os
jogadores. Eles continuam gravados no banco, porque controlam a origem oficial,
e são exibidos somente em `/gs diag` e em uma linha extra do `/gs detalhes`
para quem tem `gearscore.admin.diag`.

O plugin só envia mensagens em resposta a comandos. Nenhuma mensagem é enviada
quando o inventário ou a pontuação muda.

Outras observações:

* O ranking mostra o **último conjunto avaliado** de cada jogador. Não é
  recorde histórico: perder a melhor peça baixa a pontuação.
* No ranking, empates são decididos por quem atingiu a pontuação primeiro e,
  por fim, pelo UUID. A posição do placeholder segue exatamente essa ordem.
* `/gs reload` não recria a conexão com o banco nem muda `server`,
  `query-refresh` e `ranking`; para isso, reinicie.

### Mensagens

Todos os textos ficam em `messages.yml` (formato MiniMessage) e podem ser
alterados. As cores padrão evitam cinza escuro.

## 5. PlaceholderAPI e sidebar do TAB

Com o PlaceholderAPI instalado, a expansão é registrada sozinha.

| Placeholder | Valor |
|---|---|
| `%gearscore_score%` | pontuação total (`%gearscore_total%` é equivalente) |
| `%gearscore_tier%` | classificação, sem cores |
| `%gearscore_tier_colored%` | classificação com cores (códigos `§`) |
| `%gearscore_tier_id%` | id da faixa no `config.yml` |
| `%gearscore_armor%` | parcela das armaduras |
| `%gearscore_weapon%` | parcela das armas |
| `%gearscore_position%` | posição no ranking |

Enquanto o dado não estiver disponível, retornam `placeholders.fallback`
(padrão `—`).

Todos leem **apenas a memória**: não consultam o banco, não criam tarefas, não
leem inventários, não recalculam e não esperam. O plugin **fornece** os
valores; quem decide quando redesenhar é o TAB, o plugin de hologramas ou o
scoreboard.

As posições são atualizadas por um ciclo próprio, a cada
`ranking.position-refresh-seconds` (padrão 60 s), e só enquanto
`%gearscore_position%` estiver em uso.

A integração com a sidebar do TAB no proxy (via TAB-Bridge), com o trecho exato
de configuração, está em [TAB.md](TAB.md).

## 6. Verificar

1. Entre no New Era com equipamentos e rode `/gs` e `/gs detalhes`.
2. Guarde no inventário uma peça melhor que a equipada: a pontuação sobe sem
   precisar vesti-la, e o `/gs detalhes` passa a mostrá-la.
3. Pegue uma segunda espada igual ou pior: a pontuação não muda.
4. Jogue no chão a arma que aparece no `/gs detalhes`: a pontuação cai para a
   da próxima melhor arma. Pegue de volta: o valor retorna.
5. Mova equipamentos de slot: a pontuação não muda.
6. Rode `/gs diag`: "Banco: disponível" e "linhas gravadas" maior que zero.
7. Vá para a hub e rode `/gs`: a pontuação é a mesma. Se aparecer um valor
   anterior logo ao chegar, ele se corrige em até `join-recheck-seconds`.
8. Na hub, pegue qualquer kit e volte ao New Era: nada foi sobrescrito.

## Compilar

```bash
./mvnw clean package
```

O JAR sai em `target/GearScore-3.0.1.jar`. Para rodar o teste de carga
simulado:

```bash
./mvnw test -Pbench
```

Para rodar toda a suíte contra um MariaDB real (use um banco de testes; cada
teste cria e remove tabelas com prefixo próprio):

```bash
./mvnw test -Pmariadb "-Dgearscore.test.jdbc=jdbc:mariadb://127.0.0.1:3306/gstest?user=USUARIO&password=SENHA"
```

## Atualizando para a 3.0.1

Vale para quem está em qualquer versão anterior. Para quem já está na 3.0.0 é
só trocar o JAR: a 3.0.1 corrige a recuperação de gravações e não muda
configuração, mensagens, cálculo nem o esquema do banco. Ela passa a criar,
quando necessário, o arquivo `plugins/GearScore/pending-writes.dat` (veja
[BANCO.md](BANCO.md)); não o edite nem o apague com o servidor ligado.

Os passos abaixo descrevem a atualização vinda de versões anteriores à 3.0.0.

1. Pare o servidor, troque o JAR por `GearScore-3.0.1.jar` em **todos** os
   servidores (New Era, hub, eventos) e inicie.
2. Na primeira inicialização o plugin atualiza `config.yml` e `messages.yml`
   sozinho:
   * antes de alterar, salva uma cópia ao lado:
     `config.yml.bak-AAAAMMDD-HHMMSS` e `messages.yml.bak-AAAAMMDD-HHMMSS`;
   * cada seção de primeiro nível (`server`, `evaluation`, `enchantments`,
     `tiers`, `details`, `prefix`...) que **não foi alterada** recebe o novo
     padrão;
   * cada seção que **você alterou** é mantida exatamente como está, com
     comentários e formatação;
   * seções novas são acrescentadas e seções obsoletas não alteradas são
     removidas.
3. Leia o console. Ele lista as seções atualizadas e as que foram mantidas:

   ```
   [GearScore] config.yml updated for this version; previous file saved as config.yml.bak-...
   [GearScore] config.yml: customised sections kept as they were, please review them: server
   ```

4. O que muda nos arquivos, vindo da 2.0.0:

   | Arquivo | Seção | Mudança |
   |---|---|---|
   | `config.yml` | `rules-version` | 2 → 3 (ou +1, se você gerencia o número) |
   | `config.yml` | `tiers` | 0 / 300 / 700 / 1200 / 1800 / 2400 / 3000 → 0 / 150 / 350 / 600 / 900 / 1200 / 1400 |
   | `config.yml` | `display` | removida (`details-max-items` não existe mais) |
   | `messages.yml` | `details` | passa a listar as cinco peças, com o nome do lugar |
   | `messages.yml` | `slot` | nova: nomes de capacete, peitoral, calça, bota e arma |

   Pesos de encantamentos (`enchantments`), bases (`armor`, `weapons`) e as
   demais mensagens **não mudam** em relação à 2.0.0.

   Vindo da 1.x, mudam também `enchantments` (cada encantamento no grupo da
   peça que o recebe), `evaluation` (`weapon-slots` deixa de existir), `summary`
   e `prefix` (mensagens curtas).

5. Revise as seções que o console listar como mantidas. A mais importante é
   **`tiers`**: se você a personalizou na 2.0.0, as faixas estão na escala da
   soma do inventário e ficam altas demais para o melhor conjunto (máximo perto
   de 1400).
6. Nenhuma alteração no banco é necessária.
7. **Pontuações:** quem está online no New Era é reavaliado ao entrar. Os
   demais mantêm a pontuação antiga, da regra anterior, até entrarem de novo no
   New Era — o plugin não tem acesso ao inventário de quem está offline e não
   estima valores. A coluna `rules_version` distingue os casos:

   ```sql
   SELECT rules_version, COUNT(*) FROM gearscore_players GROUP BY rules_version;
   ```

   Enquanto houver linhas de versões antigas, o ranking mistura escalas (as da
   2.0.0 são bem maiores). Para começar o ranking do zero, apague-as com o
   servidor parado:

   ```sql
   DELETE FROM gearscore_players WHERE rules_version < 3;
   ```

8. Para a sidebar do TAB, siga [TAB.md](TAB.md).

Para desfazer: pare o servidor, restaure os arquivos `.bak-...` e volte o JAR
anterior.

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| `Database error, retrying in ...` no console | host, porta, usuário ou senha em `database.yml`; o plugin continua funcionando em memória e reconecta sozinho |
| `/gs` responde "ainda não está disponível" na hub | o jogador nunca foi avaliado em um servidor oficial, ou o banco está fora |
| Um encantamento não pontua | não há regra para ele naquele tipo de equipamento; veja `/gs diag` |
| Pontuação não muda em um mundo | o mundo está em `evaluation.ignored-worlds` |
| Item de material novo ignorado | o console lista, ao iniciar, os itens do `config.yml` que não existem na versão |
