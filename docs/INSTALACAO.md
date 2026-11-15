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

1. Copie `GearScore-1.1.0.jar` para a pasta `plugins/` de **cada** servidor
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
  `official-sources`, `query-refresh` e `ranking`; para isso, reinicie.
* No ranking, empates são decididos por quem atingiu a pontuação primeiro e,
  por fim, pelo UUID. A posição mostrada pelo placeholder segue exatamente
  essa ordem.

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

Todos os placeholders, inclusive o de posição, leem **apenas a memória**:
não consultam o banco, não criam tarefas, não leem inventários, não recalculam
e não esperam. O plugin **fornece** os valores; quem decide quando redesenhar é
o TAB, o plugin de hologramas ou o scoreboard. O GearScore não envia pacotes
nem atualiza essas interfaces por conta própria.

As posições são atualizadas por um ciclo próprio, a cada
`ranking.position-refresh-seconds` (padrão 60 s), com uma leitura agrupada para
os jogadores online no servidor, e só enquanto `%gearscore_position%` estiver
em uso. Aumentar a frequência de atualização do TAB ou dos hologramas **não**
aumenta as consultas ao banco.

Exemplos:

```yaml
# TAB (config.yml)
tabsuffix: " &8[&6%gearscore_total%&8]"

# DecentHolograms
- "&6GearScore: &f%gearscore_total% &7(%gearscore_tier%)"
```

A posição exibida muda no máximo a cada `ranking.position-refresh-seconds`,
qualquer que seja o intervalo configurado no TAB ou no plugin de hologramas.

## 6. Verificar

1. Entre no New Era, equipe uma armadura e rode `/gs` e `/gs detalhes`.
2. Troque de arma na hotbar e rode `/gs` de novo: a arma considerada muda.
3. Rode `/gs diag`: "Banco: disponível" e "linhas gravadas" maior que zero.
4. Vá para a hub e rode `/gs`: a pontuação é a mesma, com "em newera". Se
   aparecer um valor anterior logo ao chegar, ele se corrige em até
   `join-recheck-seconds`.
5. Na hub, equipe qualquer item e volte ao New Era: nada foi sobrescrito.
6. Na hub, `/gs diag` mostra as consultas agrupadas enviadas e quantas
   respostas foram aplicadas ou descartadas.

## Compilar

```bash
./mvnw clean package
```

O JAR sai em `target/GearScore-1.1.0.jar`. Para rodar o teste de carga
simulado:

```bash
./mvnw test -Pbench
```

Para rodar toda a suíte contra um MariaDB real (use um banco de testes; cada
teste cria e remove tabelas com prefixo próprio):

```bash
./mvnw test -Pmariadb "-Dgearscore.test.jdbc=jdbc:mariadb://127.0.0.1:3306/gstest?user=USUARIO&password=SENHA"
```

## Atualizando da 1.0.0 para a 1.1.0

1. **Banco:** nada a fazer manualmente. Na primeira conexão a migração 3
   adiciona a coluna gerada `rank_score`, cria o índice `idx_rank` e remove o
   `idx_score`. O usuário do banco precisa de `ALTER` e `INDEX`. Os dados
   existentes são preservados.
2. **Atualize todos os servidores.** Uma instância ainda na 1.0.0 continua
   funcionando sobre o esquema novo, mas ordena o ranking sem índice e mantém
   os problemas corrigidos nesta versão.
3. **`config.yml`:** o arquivo existente continua válido. As chaves novas
   usam o padrão quando ausentes; para ajustá-las, acrescente:

   ```yaml
   query-refresh:
     interval-seconds: 30
     join-recheck-seconds: 5
     batch-size: 100
     max-batches-per-cycle: 3

   ranking:
     position-refresh-seconds: 60
   ```

   `ranking.position-cache-seconds` deixou de existir; se estiver no arquivo,
   é ignorada.
4. **`database.yml`:** a opção `driver` foi removida. O valor `mysql` dependia
   de um driver que não fazia parte do JAR. Se a linha `driver:` estiver no
   arquivo, ela é ignorada; o driver MariaDB embutido é sempre usado.
5. Troque o JAR e reinicie.

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| `Database error, retrying in ...` no console | host, porta, usuário ou senha em `database.yml`; o plugin continua funcionando em memória e reconecta sozinho |
| `/gs` responde "ainda não está disponível" na hub | o jogador nunca foi avaliado em um servidor oficial, ou o banco está fora |
| Um encantamento não pontua | não há regra para ele naquele tipo de equipamento; veja `/gs diag` |
| Pontuação não muda em um mundo | o mundo está em `evaluation.ignored-worlds` |
| Item de material novo ignorado | o console lista, ao iniciar, os itens do `config.yml` que não existem na versão |
