# JSON:API - REST, Validation, Persistence (jsonapi-rvp)
![Build Status](https://github.com/xlate/jsonapi-rvp/workflows/build/badge.svg) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=xlate_jsonapi-rvp&metric=alert_status)](https://sonarcloud.io/dashboard?id=xlate_jsonapi-rvp) [![Maven Central](https://img.shields.io/maven-central/v/io.xlate/jsonapi-rvp)](https://search.maven.org/artifact/io.xlate/jsonapi-rvp) [![javadoc](https://javadoc.io/badge2/io.xlate/jsonapi-rvp/javadoc.svg)](https://javadoc.io/doc/io.xlate/jsonapi-rvp)

Implementation of a [JSON:API](https://jsonapi.org/) server in Java using JAX-RS, Bean Validation, and Java Persistence (JPA). This library is under active development and **APIs may not be stable**. Please open issues for feature requests or bug reports.

## Roadmap
- Simplify configuration, minimize custom interfaces/classes in client application code
- API end point generates JavaScript client module and entities
- Align client interface with JPA `EntityManager`

## Maven Coordinates

```xml
<dependency>
  <groupId>io.xlate</groupId>
  <artifactId>jsonapi-rvp</artifactId>
  <version>0.0.7</version>
</dependency>
```

## Example
```java
@Path("/blogapi")
public class BlogResources extends JsonApiResource {

    @PostConstruct
    void configure() {
        Set<JsonApiResourceType<?>> resourceTypes = new HashSet<>();

        resourceTypes.add(JsonApiResourceType.define("posts", Post.class)
                                             .build());
        resourceTypes.add(JsonApiResourceType.define("comments", Comment.class)
                                             .build());

        super.initialize(resourceTypes);
    }

}

@Entity
@Table(name = "POSTS")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column
    private String title;

    @Column
    private String text;

    @OneToMany(mappedBy = "post")
    private List<Comment> comments;

    // ... Getters and Setters
}

@Entity
@Table(name = "COMMENTS")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column
    private String text;

    @ManyToOne
    @JoinColumn(name = "post_id")
    private Post post;

    // ... Getters and Setters
}
```
Assuming a server on localhost, port 8080, JSON:API models are now available at `http://localhost:8080/blogapi/{resource-type}`. See the [JSON:API specification](https://jsonapi.org/format/) for URL conventions and message body formatting. Additionally, an ES6 client module for the API can be retrieved at `http://localhost:8080/blogapi/client.js`. The ES6 client utilizes the standard `fetch` API available in modern browsers.

### Request
```
GET /blogapi/posts/2?include=comments HTTP/1.1
Accept: application/vnd.api+json
```

### Response
```json
{
  "jsonapi":{"version":"1.0"},
  "data":{
    "id": "2",
    "type": "posts",
    "attributes": {
      "title": "Title Two",
      "text": "Text two."
    },
    "relationships": {
      "comments": {
        "links": {
          "self": "/test/posts/2/relationships/comments",
          "related": "/test/posts/2/comments"
        },
        "data": [{
          "type": "comments", "id": "2"
        }]
      },
      "author": {
        "links": {
          "self": "/test/posts/2/relationships/author",
          "related": "/test/posts/2/author"
        },
        "data": null
      }
    },
    "links": {
       "self": "/test/posts/2"
    }
  },
  "included": [{
     "type": "comments",
     "id": "2",
     "attributes": {
       "text": "Comment two."
     },
     "relationships": {
       "post": {
         "links": {
           "self": "/test/comments/2/relationships/post",
           "related": "/test/comments/2/post"
         }
       }
     },
     "links": {
       "self": "/test/comments/2"
     }
   }]
}
```
