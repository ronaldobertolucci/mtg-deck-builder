# MTG Deck Builder

Microsserviço em Java e Spring Boot para criar, organizar e analisar decks de
Magic: The Gathering. O serviço mantém os decks de cada usuário no PostgreSQL,
autentica requisições com JWT e consulta o [MTG Card Manager](https://github.com/ronaldobertolucci/mtg-card-manager) para obter metadados e
legalidades das cartas pela identidade estável `oracle_id`.

O projeto está em desenvolvimento. O MVP suporta **STANDARD, MODERN, PIONEER,
LEGACY e COMMANDER**, incluindo múltiplos comandantes compatíveis e uma zona
opcional de companion. As condições específicas de construção de cada companion
ainda não são verificadas.

## Tecnologias

- Java 25 e Spring Boot 4.1.1
- Spring Web MVC, Spring Security e JWT com Auth0 Java JWT
- Spring Data JPA, Hibernate e Bean Validation
- PostgreSQL 16 e Flyway
- Spring RestClient e Caffeine Cache
- Jakarta Mail para confirmação de e-mail e recuperação de senha
- Springdoc OpenAPI e Swagger UI
- JUnit 5, Mockito, MockMvc e Testcontainers
- H2 para parte dos testes
- Maven Wrapper, Docker Compose e Nginx

## Modelo de dados e identidade das cartas

Os dados de autenticação e os decks são persistidos no PostgreSQL. Os metadados das
cartas permanecem no Card Manager; o Deck Builder guarda referências por `oracle_id`.

### `decks`

Representa um deck pertencente a um usuário:

| Campo | Descrição |
| --- | --- |
| `id` | UUID gerado para o deck. |
| `user_id` | Identificador do usuário autenticado. |
| `name` | Nome do deck, até 255 caracteres. |
| `format` | STANDARD, MODERN, PIONEER, LEGACY ou COMMANDER. |
| `status` | REGULAR, IRREGULAR ou UNDEFINED. |
| `analyzed_at` | Data da última análise válida para o conteúdo atual, ou nulo. |
| `created_at`, `updated_at` | Datas de criação e atualização. |

Decks novos começam com status `UNDEFINED`. As explicações da última análise são
persistidas em `deck_analysis_messages`, em ordem, vinculadas ao deck.

### `deck_cards`

Cada registro representa a quantidade de uma carta em uma zona:

| Campo | Descrição |
| --- | --- |
| `id` | UUID gerado para o registro. |
| `deck_id` | Referência ao deck, com exclusão em cascata. |
| `oracle_id` | UUID estável da carta no catálogo. Não é o ID de uma impressão. |
| `quantity` | Quantidade positiva. Na zona COMPANION, deve ser 1. |
| `board_type` | MAINBOARD, SIDEBOARD, COMMANDER ou COMPANION. |

A restrição única `(deck_id, oracle_id, board_type)` impede registros duplicados da
mesma carta na mesma zona. Uma carta pode aparecer em zonas diferentes, mas seus
limites de cópias são avaliados em conjunto. Quantidade zero é um comando de remoção
na API; não é persistida.

As migrações também criam usuários, papéis, vínculos de autorização e tokens para
confirmação de e-mail e recuperação de senha.

## Execução com Docker

### Pré-requisitos

- Docker com o plugin Docker Compose
- MTG Card Manager acessível pela rede do contêiner e com catálogo carregado
- Serviço SMTP para os fluxos de cadastro e recuperação de senha
- Arquivo `.env` preenchido para o profile `prod`, usado pelo Compose

### Preparar o ambiente

Se ainda não existir um `.env`, copie o modelo e substitua seus valores de exemplo:

```bash
cp .env.example .env
```

### Acesso ao Card Manager: host e redes Docker

```dotenv
CARD_MANAGER_URL={URL que aponta para o MTG Card Manager}
CARD_MANAGER_ORACLE_DETAILS_PATH=/cards/{oracleId}
```

### Iniciar API e PostgreSQL

```bash
docker compose up --build -d
```

| Recurso | Nome |
| --- | --- |
| API | `mtg-deck-builder-app` |
| PostgreSQL | `mtg-deck-builder-db` |
| Proxy Nginx | `mtg-deck-builder-nginx` |
| Volume lógico | `postgres-data` |
| Rede | Rede padrão criada pelo Compose para o projeto |

A API fica disponível pelo Nginx em `http://localhost/api`. O Compose não publica
diretamente as portas 8080 da aplicação ou 5432 do banco. O nome efetivo do volume
recebe o prefixo do projeto Compose.

Flyway aplica as migrações pendentes durante a inicialização. Swagger UI e OpenAPI
ficam desabilitados no profile `prod`. Não há endpoint dedicado de health check
implementado atualmente.

```bash
docker compose logs -f app
docker compose down
```

### Recriar o banco de desenvolvimento

Normalmente, as alterações do schema são aplicadas pelo Flyway. Para descartar
intencionalmente todos os dados locais e começar novamente:

```bash
docker compose down --volumes --remove-orphans
docker compose up --build -d
```

> `docker compose down --volumes` apaga os dados do PostgreSQL, incluindo usuários,
> decks e tokens persistidos.

## Configuração

A configuração usa `application.properties` e arquivos específicos para `dev`,
`test` e `prod`. O `.env` é utilizado pelo Docker Compose; ao executar Java ou Maven
diretamente, exporte as variáveis necessárias no shell ou configure-as na IDE.

| Variável | Padrão ou uso | Descrição |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` no Compose | Profile da aplicação; use `dev` na execução local. |
| `SERVER_PORT` | `8080` | Porta HTTP interna. O contexto da aplicação é `/api`. |
| `DATASOURCE_URL` | Obrigatória em prod | URL JDBC do PostgreSQL. |
| `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD` | Obrigatórias em prod | Credenciais JDBC. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Exigidas pelo Compose | Inicialização do contêiner PostgreSQL. |
| `API_SECURITY_TOKEN_SECRET` | Obrigatória em prod | Segredo de assinatura JWT. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` em dev | Origens autorizadas pelo CORS. |
| `CARD_MANAGER_URL` | `http://localhost:8000` em dev; vazio na configuração comum | URL base do Card Manager. |
| `CARD_MANAGER_ORACLE_DETAILS_PATH` | `/cards/{oracleId}` | Caminho de consulta; deve conter `{oracleId}`. |
| `APP_EMAIL_FROM` | `dev@localhost` em dev | Remetente dos e-mails. |
| `APP_FRONTEND_URL` | `http://localhost:4200` em dev | URL do frontend usada nos links dos e-mails. |
| `APP_NAME` | `MTG-Deck-Builder-DEV` em dev | Nome da aplicação nos e-mails. |
| `APP_EMAIL_VERIFICATION_EXPIRY_HOURS` | `24` em dev | Validade do token de confirmação. |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` em dev | Servidor SMTP. |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Vazios em dev | Credenciais SMTP. |
| `MAIL_AUTH`, `MAIL_STARTTLS` | `false` em dev | Autenticação e STARTTLS; use valores booleanos. |
| `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` | `24` em dev | Validade do token de recuperação. |
| `PASSWORD_RESET_CLEANUP_CRON` | `-` em dev | Agendamento de limpeza; `-` desabilita a execução. |

Os valores de dev são definidos no arquivo do profile; as variáveis personalizadas
de e-mail e banco acima são usadas como placeholders em prod. Para sobrescrever
propriedades locais, use os parâmetros do Spring ou as variáveis correspondentes,
como `SPRING_DATASOURCE_URL`.

O JWT possui validade configurada de duas horas. O cache `cards` usa Caffeine com
até 10.000 entradas e expiração de 24 horas após o último acesso. A configuração
atual do Compose só encaminha as variáveis declaradas em `environment`; acrescente
outras nesse bloco ou em um override quando necessário.

## Integração com o Card Manager

O Deck Builder consulta o serviço responsável pelo catálogo:

```http
GET /cards/{oracleId}?lang=en
```

O parâmetro `lang=en` é sempre enviado porque as regras de override e de comandantes
interpretam os textos oficiais em inglês. O DTO utiliza `oracle_id`, `name`,
`type_line`, `oracle_text`, `color_identity`, `legalities` e `keywords`.

1. Na criação de Commander e na inclusão ou atualização de cartas, o serviço obtém
   os metadados usando cache por oracle ID.
2. A integração converte falhas HTTP 4xx em carta não encontrada e falhas 5xx ou de
   comunicação em indisponibilidade do Card Manager.
3. Na análise completa, cada oracle ID é consultado novamente, sem reutilizar a
   entrada anterior do cache; uma resposta bem-sucedida atualiza esse cache.

Este microsserviço não importa o Bulk Data nem sincroniza diretamente com o
Scryfall. A atualização do catálogo é responsabilidade do MTG Card Manager. Uma
consulta nova reflete os dados atualmente disponíveis nesse serviço.

## API

Base local: `http://localhost:8080/api`. Pelo Compose/Nginx: `http://localhost/api`.
Os caminhos abaixo incluem o contexto `/api` uma única vez.

Os endpoints de decks exigem, com exceção do endpoint de análise:

```http
Authorization: Bearer <token>
Content-Type: application/json
```

O proprietário é obtido do usuário autenticado. Não envie `user_id` no corpo.

### Autenticação e conta

| Método | Caminho | Finalidade |
| --- | --- | --- |
| POST | `/api/auth/register` | Cadastrar usuário e iniciar confirmação de e-mail. |
| GET | `/api/auth/verify-email?token=...` | Confirmar e-mail. |
| POST | `/api/auth/resend-verification` | Reenviar confirmação, com `email` no corpo. |
| POST | `/api/auth/login` | Autenticar com `email` e `password`. |
| POST | `/api/password/forgot` | Solicitar recuperação, com `email` no corpo. |
| GET | `/api/password/reset/validate?token=...` | Validar token de recuperação. |
| POST | `/api/password/reset` | Redefinir senha, com `token` e `newPassword`. |

Exemplo de cadastro:

```json
{
  "firstName": "Maria",
  "lastName": "Silva",
  "email": "maria@example.com",
  "dateOfBirth": "1995-01-20",
  "password": "senha-de-exemplo-123"
}
```

O cadastro retorna `201 Created`. Após confirmar o e-mail, faça login:

```json
{
  "email": "maria@example.com",
  "password": "senha-de-exemplo-123"
}
```

A resposta do login contém `token`, `type` (Bearer), `expiresIn` em segundos e `user`.
Use o token nas próximas requisições.

## Criação e edição de decks

### Estatísticas do deck

`GET /api/decks/{deckId}/stats` retorna HTTP 200 com `totalCards`, `averageCmc`,
`manaCurve`, `typeDistribution`, `colorPips` e `rarityDistribution`. Exige autenticação
e propriedade do deck; deck inexistente ou de outro usuário retorna 404.

Somente MAINBOARD e COMMANDER participam das estatísticas, com contagens ponderadas
pela quantidade de cópias. Terrenos entram no total, nos tipos e nas raridades,
mas ficam fora da curva, dos pips e do CMC médio. A média usa o CMC original e é
arredondada para duas casas decimais; sem cartas não-terreno, retorna zero.
A curva arredonda o CMC para inteiro e contém sempre `0` a `6` e `7+`.

Cada carta pertence a um único tipo, seguindo a prioridade Land, Creature,
Planeswalker, Instant, Sorcery, Artifact, Enchantment e Other. Os pips contam
cada ocorrência de W, U, B, R, G e C por bloco `{...}` do custo de mana, uma vez
por cor no bloco e multiplicada pela quantidade de cópias, com chaves WHITE,
BLUE, BLACK, RED, GREEN e COLORLESS. Híbridos contam para ambas as cores:
`{G/W}` soma GREEN e WHITE; `{B/P}` soma BLACK; `{2/W}` soma WHITE e `{C}` soma
COLORLESS. Blocos genéricos como `{1}`, `{2}` e `{X}` não somam pips. As raridades usam COMMON,
UNCOMMON, RARE e MYTHIC. Todas as categorias são inicializadas com zero.
Os metadados são consultados pelo oracle ID, aproveitando o cache do Card Manager.

### Sugestão de base de mana

`GET /api/decks/{deckId}/mana-suggestion?targetLands=36` retorna HTTP 200:

```json
{"suggestedBasicLands":{"WHITE":0,"BLUE":18,"BLACK":0,"RED":0,"GREEN":18}}
```

`targetLands` é inteiro, opcional (padrão 36) e não negativo; valores inválidos
retornam 400. Exige autenticação e propriedade do deck, com 404 para deck
inexistente ou de outro usuário. Usa os metadados em cache de MAINBOARD e COMMANDER.

> Plataformas como EDHREC, Moxfield e Archidekt adotam 36 como a constante neutra padrão porque ela atende a maioria 
esmagadora dos decks com curva de mana média entre 2.5 e 3.5.

A demanda conta pips coloridos por cópia, incluindo ambas as cores dos híbridos.
Terrenos não participam. Cada cor de `produced_mana` abate uma unidade por cópia
somente para geradores não-terreno com `cmc >= 2`. Geradores com CMC menor que 2
ou desconhecido não reduzem a demanda. A demanda líquida de cada cor nunca é negativa.
O algoritmo do maior resto distribui exatamente `targetLands`, com desempate na
ordem WHITE, BLUE, BLACK, RED, GREEN. Havendo demanda, todas essas chaves aparecem,
inclusive as de quantidade zero. Sem demanda líquida, retorna
`{"suggestedBasicLands":{}}`. A sugestão não modifica o deck.

### Exportar deck

`GET /api/decks/{deckId}/export?format=ARENA` retorna HTTP 200 com `ExportDeckResponse`:

```json
{
  "content": "Commander\n1 Ghalta, Primal Hunger\n\nDeck\n99 Forest"
}
```

O parâmetro `format` é opcional e aceita `ARENA` (padrão) ou `PLAIN_TEXT`.
Arena usa os blocos Commander, Companion, Deck e Sideboard, omitindo zonas vazias.
Texto puro começa pelo Mainboard sem cabeçalho; Commander, Companion e Sideboard
mantêm cabeçalhos para preservar suas zonas. Blocos são separados por uma linha
em branco, sem quebra de linha final. As cartas são ordenadas por nome dentro de cada zona.

Exige autenticação e propriedade do deck; deck inexistente ou de outro usuário
retorna 404. O serviço resolve nomes por oracle ID no Card Manager usando o cache
existente. Um deck vazio retorna `content` vazio; a exportação não altera nem analisa o deck.

### Importar deck de texto

`POST /api/decks/import` recebe o nome, formato e texto da lista:

```json
{
  "name": "Meu deck Modern",
  "format": "MODERN",
  "rawText": "Deck\n4 Lightning Bolt (M11) 146\nSideboard\n2 Duress"
}
```

Requer autenticação e retorna `201 Created`, `Location` e o deck com suas cartas.
Cada linha usa `quantidade nome`; os dados de coleção do Arena são descartados.
Os cabeçalhos `Deck`, `Maindeck`, `Sideboard`, `Commander` e `Companion` aceitam
maiúsculas/minúsculas e dois-pontos opcionais. Sem cabeçalho, a zona é MAINBOARD.
Linhas vazias são ignoradas; linhas inválidas e quantidades não positivas são rejeitadas.
Linhas repetidas da mesma carta e zona têm as quantidades somadas.

A busca por nome usa `/cards/search?lang=en&name_exact={name}&limit=1` no Card Manager,
o primeiro resultado e o cache exclusivo `cards_by_name`, cuja chave preserva maiúsculas e minúsculas.
O nome deve corresponder exatamente à grafia no Card Manager (por exemplo, `Lightning Bolt`, não `lightning bolt`). As regras existentes do
formato são aplicadas; qualquer falha desfaz toda a importação, incluindo o deck.
O deck permanece com status UNDEFINED até a análise.
Na busca por nome, HTTP 404 do Card Manager é tratado como carta não encontrada e
retorna 422 com o nome informado. Falhas de conexão e respostas 5xx retornam 503.

### Criar deck

```http
POST /api/decks
```

```json
{
  "name": "Meu deck Modern",
  "format": "MODERN"
}
```

| Campo | Obrigatório | Regra |
| --- | --- | --- |
| `name` | Sim | Não vazio; até 255 caracteres. |
| `format` | Sim | Um dos cinco formatos suportados. |
| `commanderOracleIds` | Em COMMANDER | Um ou dois UUIDs distintos de comandantes compatíveis. Nos outros formatos, omita ou envie lista vazia. |

Exemplo com The Tenth Doctor e Rose Tyler:

```json
{
  "name": "Doctor + Rose",
  "format": "COMMANDER",
  "commanderOracleIds": [
    "23af0a0a-1d12-47c7-b191-2bd3f84eea93",
    "2f56de72-80d7-48c6-9b57-84fbd252c699"
  ]
}
```

Retorna `201 Created`, cabeçalho `Location` e o deck criado. Os comandantes são
incluídos na zona COMMANDER com quantidade 1, após validar seus metadados e regras.
O status inicial é UNDEFINED. O endereço de `Location` identifica o recurso;
ainda não existe endpoint GET de consulta individual de decks.

### Adicionar, atualizar ou remover uma carta

```http
PUT /api/decks/{deckId}/cards
```

```json
{
  "oracleId": "b2c6aa39-2d2a-459c-a555-fb48ba993373",
  "boardType": "MAINBOARD",
  "quantity": 60
}
```

Este exemplo adiciona 60 cópias de Island. A quantidade substitui o total da carta
na zona; não é um incremento. Envie `quantity: 0` para remover o registro.

- `oracleId`, `boardType` e `quantity` são obrigatórios.
- Quantidades negativas são rejeitadas.
- Um upsert bem-sucedido retorna `200 OK` com o deck e invalida a análise anterior:
  `status: UNDEFINED`, `analyzedAt: null` e `analysisMessages: []`.
- Essa invalidação também ocorre quando a requisição repete a quantidade existente.
- Deck inexistente ou pertencente a outro usuário retorna 404.

### Legalidades e limites de cópias

| Legalidade no formato | Comportamento |
| --- | --- |
| LEGAL | Aplica o limite do formato e os overrides. |
| RESTRICTED | Limita a uma cópia, mesmo que haja override. |
| BANNED ou NOT_LEGAL | Rejeita a inclusão. |
| Ausente ou desconhecida | Rejeita a inclusão por falta de legalidade confirmada. |

Formatos construídos usam limite base de quatro cópias; Commander usa uma. Terrenos
básicos e o texto `A deck can have any number of cards named` permitem quantidade
ilimitada. O texto `A deck can have up to ... cards named` define um limite específico.
As cópias são somadas entre as zonas, incluindo companion.

RESTRICTED é tratado pelo domínio, embora VINTAGE não seja um formato suportado.

### Comandantes e companion

A aplicação reconhece pares com Partner, Partner with, Friends Forever,
Doctor's companion e Choose a Background, conforme as regras implementadas para
cada combinação. A identidade de cor do deck é a união das identidades dos
comandantes. As cartas precisam respeitar essa identidade.

**Doctor's companion e Companion são habilidades diferentes.** Doctor e seu
Doctor's companion ocupam COMMANDER: são dois comandantes e deixam 98 cartas na
mainboard. Uma carta com a palavra-chave `Companion`, como Keruga, ocupa a zona
COMPANION quando escolhida como companheiro e fica fora das 100 cartas.

Um deck pode ter no máximo um companion, com quantidade 1. Em formatos construídos,
sideboard + companion não pode ultrapassar 15 cartas. Em Commander não há sideboard;
companion é a única zona externa permitida. Suas condições específicas de construção
ainda não são validadas no MVP.

## Análise de decks

```http
POST /api/decks/{deckId}/analysis
```

Não exige corpo. Reconsulta os metadados das cartas e persiste o resultado, os motivos
e a data. Retorna `200 OK` mesmo quando encontra um deck irregular.

| Formato | Verificações de tamanho |
| --- | --- |
| STANDARD, MODERN, PIONEER e LEGACY | Mainboard com pelo menos 60 cartas; sideboard + companion com no máximo 15; sem comandantes. |
| COMMANDER | Exatamente 100 cartas entre mainboard e comandantes; um ou dois comandantes válidos; nenhum sideboard. Companion fica fora das 100. |

Além do tamanho, revalida legalidades, limites de cópias, overrides, compatibilidade
dos comandantes, identidade de cor e requisitos básicos da zona COMPANION.

| Status | Significado |
| --- | --- |
| REGULAR | Todas as verificações implementadas passaram e não há incerteza identificada. |
| IRREGULAR | Existe uma violação confirmada. Tem prioridade sobre incertezas. |
| UNDEFINED | Sem análise atual, ou com companion/metadata indisponível/legalidade desconhecida, sem violação já confirmada. |

Exemplo ilustrativo da estrutura da resposta para um deck Modern vazio analisado:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "Meu deck Modern",
  "format": "MODERN",
  "createdAt": "2026-09-16T12:00:00Z",
  "updatedAt": "2026-09-16T12:01:00Z",
  "cards": [],
  "status": "IRREGULAR",
  "analyzedAt": "2026-09-16T12:01:00Z",
  "analysisMessages": [
    "Constructed mainboard requires at least 60 cards."
  ]
}
```

Cada item de `cards` contém `id`, `oracleId`, `boardType` e `quantity`. Decks com
companion recebem uma explicação sobre a ausência de validação de suas condições
específicas. Falhas de consulta durante a análise são registradas como incerteza;
não são tratadas como prova de que a carta foi banida.

A análise é uma fotografia dos dados disponíveis naquele momento. Alterações de
legalidade no catálogo exigem uma nova chamada; não existe reanálise agendada.
O status REGULAR não representa certificação oficial de torneio nem implementação
integral de todas as regras de Magic.

### Respostas de erro

Erros de domínio e validação dos endpoints de decks utilizam Problem Details,
com `Content-Type: application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "The two commanders do not have compatible partner abilities",
  "instance": "/api/decks"
}
```

| Status | Motivo |
| --- | --- |
| 400 Bad Request | Corpo, tipo ou campos inválidos; erros de Bean Validation incluem `errors` com campo e mensagem. |
| 404 Not Found | Deck inexistente/não pertencente ao usuário ou carta não encontrada por oracle ID. Na importação por nome, carta não encontrada retorna 422. |
| 422 Unprocessable Entity | Violação de regra ao criar ou editar o deck. |
| 503 Service Unavailable | Card Manager indisponível durante criação ou edição. |

Os fluxos de autenticação também possuem tratamentos próprios de erro; nem todas as
respostas da aplicação usam o mesmo envelope. Não há, por enquanto, endpoints de
listagem, exclusão de deck ou alteração de nome/formato.

## Desenvolvimento local

Disponibilize Java 25, PostgreSQL, Docker para Testcontainers e os serviços Card
Manager/SMTP necessários aos fluxos que serão exercitados.

O profile `dev` usa PostgreSQL em `localhost:5432/postgres`, usuário e senha
`postgres`, Card Manager em `localhost:8000` e SMTP em `localhost:1025`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Recursos disponíveis em dev:

- API: `http://localhost:8080/api`
- Swagger UI: `http://localhost:8080/api/swagger-ui/index.html`
- OpenAPI: `http://localhost:8080/api/v3/api-docs`

Para uma conexão JDBC diferente:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mtg_deck_builder \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### Testes e build

```bash
./mvnw test
./mvnw package
```

A suíte combina testes unitários, WebMvcTest, MockRestServiceServer e testes de
persistência com PostgreSQL via Testcontainers. O Docker precisa estar acessível;
os testes PostgreSQL não são substituídos pelo H2 do profile `test`. Os testes de
integração HTTP simulam o Card Manager e não dependem de um catálogo real.

O build do Dockerfile pula os testes; execute a suíte separadamente antes de validar
uma entrega. Não use as credenciais e o segredo JWT de desenvolvimento fora desse
ambiente.

## Créditos e aviso legal

Este projeto utiliza o **MTG Card Manager** como serviço de catálogo. Os dados de
cartas consultados por essa integração têm origem no [Scryfall](https://scryfall.com/)
e em seu [Bulk Data](https://scryfall.com/docs/api/bulk-data). Agradecemos ao Scryfall
por disponibilizar essa infraestrutura à comunidade. O Deck Builder não é afiliado,
patrocinado ou endossado pelo Scryfall, e não realiza a importação direta do catálogo.

Magic: The Gathering, nomes de cartas, textos, símbolos, imagens e demais materiais
relacionados pertencem a seus respectivos titulares, incluindo a Wizards of the
Coast e, quando aplicável, titulares de propriedades intelectuais licenciadas.

**MTG Deck Builder é um projeto de fãs não oficial, sem aprovação, patrocínio ou
endosso da Wizards of the Coast.** Parte dos materiais referenciados pertence à
Wizards of the Coast. © Wizards of the Coast LLC.

A finalidade do software é auxiliar a organização, construção e análise técnica de
decks. Ele não foi criado para fabricar cartas falsificadas ou proxies, redistribuir
obras protegidas sem autorização, substituir produtos oficiais ou facilitar pirataria.
O acesso aos dados por meio de uma API não transfere direitos sobre os materiais.

Quem operar, publicar ou distribuir uma instalação deve respeitar os direitos,
licenças e termos aplicáveis. Consulte a
[Política de Conteúdo de Fãs da Wizards of the Coast](https://company.wizards.com/pt-BR/legal/fancontentpolicy)
antes de publicar ou explorar o projeto; este aviso não constitui declaração de
aprovação pela Wizards nem substitui o cumprimento dessa política.

Os créditos e avisos sobre dados e marcas não concedem uma licença para o código-fonte.
O repositório ainda não contém um arquivo LICENSE que defina sua licença de distribuição.
