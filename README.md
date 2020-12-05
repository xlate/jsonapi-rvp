# JSON:API - REST, Validation, Persistence (jsonapi-rvp)
![Build Status](https://github.com/xlate/jsonapi-rvp/workflows/build/badge.svg) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=xlate_jsonapi-rvp&metric=alert_status)](https://sonarcloud.io/dashboard?id=xlate_jsonapi-rvp) [![Maven Central](https://img.shields.io/maven-central/v/io.xlate/jsonapi-rvp)](https://search.maven.org/artifact/io.xlate/jsonapi-rvp) [![javadoc](https://javadoc.io/badge2/io.xlate/jsonapi-rvp/javadoc.svg)](https://javadoc.io/doc/io.xlate/jsonapi-rvp)

Implementation of a [JSON:API](https://jsonapi.org/) server in Java using JAX-RS, Bean Validation, and Java Persistence (JPA).

## Roadmap
- Simplify configuration, minimize custom interfaces/classes in client application code
- API end point generates JavaScript client module and entities
- Align client interface with JPA `EntityManager`

## Maven Coordinates

```xml
<dependency>
  <groupId>io.xlate</groupId>
  <artifactId>jsonapi-rvp</artifactId>
  <version>0.0.6</version>
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
Assuming a server on localhost, port 8080, JSON:API models are now available at `http://localhost:8080/blogapi/{resource-type}`. See the [JSON:API specification](https://jsonapi.org/format/) for URL conventions and message body formatting.
