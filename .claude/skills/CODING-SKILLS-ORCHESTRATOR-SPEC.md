# Coding Skills Orchestrator - Complete Specification

**Version:** 1.0  
**Date:** 2026-10-08  
**Status:** Active  

---

## Table of Contents

1. [Overview](#overview)
2. [Document Formats](#document-formats)
3. [Orchestrator Structure](#orchestrator-structure)
4. [Sub-Skill Structure](#sub-skill-structure)
5. [API Contracts](#api-contracts)
6. [Configuration Schemas](#configuration-schemas)
7. [Execution Protocols](#execution-protocols)
8. [Result Formats](#result-formats)
9. [Error Handling](#error-handling)
10. [File Organization](#file-organization)

---

## Overview

The Coding Skills Orchestrator is a multi-agent system that:
- **Routes** tasks to specialized coding skills based on tags
- **Coordinates** execution of multiple skills for complex tasks
- **Aggregates** results from multiple sub-skills
- **Updates** Jira with complete results
- **Handles** errors and fallbacks gracefully

### Architecture Layers

```
┌──────────────────────────────────────┐
│  Jira Integration Layer              │  Read tasks, update status
├──────────────────────────────────────┤
│  Orchestrator (Main Agent)           │  Route, coordinate, aggregate
├──────────────────────────────────────┤
│  Sub-Skill Agents (Specialized)      │  Generate code per domain
├──────────────────────────────────────┤
│  Code Generation Templates           │  Language-specific templates
├──────────────────────────────────────┤
│  Git & CI/CD Integration             │  Create PRs, run tests
└──────────────────────────────────────┘
```

---

## Document Formats

### 1. Task Input Document (from Jira)

**File:** `task-<TASK_KEY>.json`  
**Source:** Jira REST API `/rest/api/3/issue/<TASK_KEY>`  
**Purpose:** Input to orchestrator describing what to build  

```json
{
  "metadata": {
    "version": "1.0",
    "format": "coding-task-input",
    "generatedAt": "2026-10-08T10:30:00Z"
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint",
    "description": "Create a REST API endpoint that accepts student application data, validates it, stores it in the database, and returns a reference ID.",
    
    "story": {
      "key": "OT-4",
      "summary": "Student submits college application",
      "epic": {
        "key": "OT-1",
        "summary": "Student Admissions"
      }
    },
    
    "tags": [
      "spring-boot",
      "api",
      "rest-endpoint",
      "validation",
      "database",
      "error-handling"
    ],
    
    "priority": "High",
    "storyPoints": 5,
    
    "acceptanceCriteria": [
      "POST /api/v1/applications accepts JSON with personal info, academic history, files",
      "Validates all required fields (name, email, phone, academic background)",
      "Returns 400 Bad Request with field-specific errors for invalid data",
      "Returns 409 Conflict if application already exists for email",
      "Generates and returns unique reference ID (APP-12345)",
      "Stores application with tenant ID for multi-tenancy isolation",
      "Returns 201 Created on success with reference ID"
    ],
    
    "technicalNotes": [
      "Use Spring Boot REST endpoints",
      "Implement input validation using Bean Validation (@Valid, @NotNull, etc)",
      "Check for duplicate submissions by email address per tenant",
      "Store in ArcadeDB with tenant isolation",
      "Generate reference ID using format: APP-<TIMESTAMP>-<RANDOM>",
      "Return error responses following RFC 7807 (Problem Details)"
    ],
    
    "dependencies": {
      "tasks": ["OT-17"],
      "services": ["application-service", "storage-service"],
      "databases": ["arcadedb"]
    },
    
    "testing": {
      "unitTests": {
        "required": true,
        "minimumCoverage": 90,
        "framework": "JUnit 5"
      },
      "integrationTests": {
        "required": true,
        "framework": "Testcontainers"
      }
    }
  }
}
```

---

### 2. Routing Decision Document (Orchestrator Output)

**File:** `routing-<TASK_KEY>.json`  
**Purpose:** Documents routing decision for audit trail  

```json
{
  "metadata": {
    "version": "1.0",
    "format": "routing-decision",
    "generatedAt": "2026-10-08T10:30:30Z",
    "orchestratorVersion": "1.0",
    "orchestratorId": "coding-orchestrator-v1"
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint"
  },
  
  "analysis": {
    "phase": "tag-analysis",
    "tagsDetected": [
      "spring-boot",
      "api",
      "rest-endpoint",
      "validation",
      "database",
      "error-handling"
    ],
    
    "tagMappings": [
      {
        "tag": "spring-boot",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.95,
        "isPrimary": true,
        "reason": "Core backend framework"
      },
      {
        "tag": "api",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.90,
        "isPrimary": false,
        "reason": "Secondary indicator for REST API work"
      },
      {
        "tag": "rest-endpoint",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.90,
        "isPrimary": false,
        "reason": "Spring Boot handles REST endpoints"
      },
      {
        "tag": "validation",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.85,
        "isPrimary": false,
        "reason": "Validation handled by Spring Boot framework"
      },
      {
        "tag": "database",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.80,
        "isPrimary": false,
        "reason": "Spring Data JPA handles database layer"
      },
      {
        "tag": "error-handling",
        "mappedSkill": "spring-boot-coding",
        "confidence": 0.75,
        "isPrimary": false,
        "reason": "Spring Boot error handling patterns"
      }
    ]
  },
  
  "decision": {
    "phase": "consolidation",
    "primarySkills": [
      "spring-boot-coding"
    ],
    "secondarySkills": [],
    "fallbackSkill": "general-coding-skill",
    "overallConfidence": 0.89,
    "executionMode": "SEQUENTIAL",
    "estimatedDuration": {
      "min": 300,
      "max": 600,
      "unit": "seconds"
    },
    "rationale": "Task is primarily Spring Boot REST API development. Single skill sufficient to handle all tags."
  },
  
  "execution": {
    "phase": "execution-plan",
    "steps": [
      {
        "sequence": 1,
        "skill": "spring-boot-coding",
        "action": "invoke",
        "mode": "synchronous",
        "timeout": 600,
        "retry": 2
      }
    ],
    "dependencies": [],
    "parallelizable": false
  },
  
  "auditTrail": {
    "decidedBy": "orchestrator-v1",
    "decidedAt": "2026-10-08T10:30:30Z",
    "correlationId": "task-OT-18-routing-20261008-103030"
  }
}
```

---

### 3. Sub-Skill Input Document (Orchestrator → Sub-Skill)

**File:** `<SKILL_NAME>-input-<TASK_KEY>.json`  
**Purpose:** Input from orchestrator to a specific sub-skill  

```json
{
  "metadata": {
    "version": "1.0",
    "format": "sub-skill-input",
    "skillName": "spring-boot-coding",
    "skillVersion": "1.0",
    "generatedAt": "2026-10-08T10:30:30Z"
  },
  
  "orchestrator": {
    "id": "coding-orchestrator-v1",
    "routingDecisionId": "task-OT-18-routing-20261008-103030",
    "orchestratorVersion": "1.0"
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint",
    "description": "Create a REST API endpoint that accepts student application data...",
    
    "relevantTags": [
      "spring-boot",
      "api",
      "rest-endpoint",
      "validation",
      "database"
    ],
    
    "acceptanceCriteria": [
      "POST /api/v1/applications accepts JSON with personal info...",
      "Validates all required fields...",
      "Returns 400 Bad Request with field-specific errors..."
    ]
  },
  
  "context": {
    "story": {
      "key": "OT-4",
      "summary": "Student submits college application"
    },
    "epic": {
      "key": "OT-1",
      "summary": "Student Admissions"
    },
    "project": {
      "key": "OT",
      "name": "Orphos - EIP"
    }
  },
  
  "requirements": {
    "language": "Java",
    "framework": "Spring Boot",
    "frameworks": ["Spring Boot", "Spring Data JPA", "Spring Validation"],
    "testFramework": "JUnit 5",
    "buildTool": "Maven",
    "minimumCodeCoverage": 90
  },
  
  "codeGeneration": {
    "outputFormat": "modular-classes",
    "generateTests": true,
    "generateDocumentation": true,
    "createPullRequest": true,
    "targetBranch": "main",
    "outputDirectory": "src/main/java/com/appverse/applications"
  },
  
  "templates": {
    "available": [
      "rest-controller.template",
      "service-class.template",
      "repository.template",
      "validation.template",
      "error-handling.template",
      "unit-test.template"
    ],
    "templateDirectory": ".claude/skills/spring-boot-coding/templates"
  },
  
  "database": {
    "type": "ArcadeDB",
    "schema": {
      "entityName": "StudentApplication",
      "fields": [
        {
          "name": "id",
          "type": "String",
          "constraints": ["primary-key", "not-null"]
        },
        {
          "name": "email",
          "type": "String",
          "constraints": ["unique-per-tenant", "not-null"]
        },
        {
          "name": "firstName",
          "type": "String",
          "constraints": ["not-null"]
        },
        {
          "name": "lastName",
          "type": "String",
          "constraints": ["not-null"]
        },
        {
          "name": "tenantId",
          "type": "String",
          "constraints": ["not-null", "indexed"]
        },
        {
          "name": "createdAt",
          "type": "DateTime",
          "constraints": ["not-null"]
        }
      ]
    }
  },
  
  "apiContract": {
    "endpoint": {
      "method": "POST",
      "path": "/api/v1/applications",
      "description": "Submit a new student application"
    },
    "requestBody": {
      "format": "application/json",
      "required": true,
      "fields": [
        {
          "name": "firstName",
          "type": "string",
          "required": true,
          "validation": ["not-blank", "max-length:100"]
        },
        {
          "name": "lastName",
          "type": "string",
          "required": true,
          "validation": ["not-blank", "max-length:100"]
        },
        {
          "name": "email",
          "type": "string",
          "required": true,
          "validation": ["email-format", "unique-per-tenant"]
        }
      ]
    },
    "responses": {
      "201": {
        "description": "Application created successfully",
        "body": {
          "id": "string",
          "referenceId": "string",
          "createdAt": "datetime"
        }
      },
      "400": {
        "description": "Validation error",
        "body": {
          "errors": [
            {
              "field": "email",
              "message": "Email is required"
            }
          ]
        }
      },
      "409": {
        "description": "Application already exists",
        "body": {
          "message": "Application already submitted from this email"
        }
      }
    }
  },
  
  "options": {
    "generateDocumentation": true,
    "documentationFormat": "OpenAPI 3.0",
    "includeLogging": true,
    "includeMetrics": true,
    "includeErrorHandling": true
  },
  
  "auditTrail": {
    "routingDecisionId": "task-OT-18-routing-20261008-103030",
    "sentBy": "orchestrator-v1",
    "sentAt": "2026-10-08T10:30:31Z",
    "correlationId": "task-OT-18-routing-20261008-103030"
  }
}
```

---

### 4. Sub-Skill Output Document (Sub-Skill → Orchestrator)

**File:** `<SKILL_NAME>-output-<TASK_KEY>.json`  
**Purpose:** Result from sub-skill back to orchestrator  

```json
{
  "metadata": {
    "version": "1.0",
    "format": "sub-skill-output",
    "skillName": "spring-boot-coding",
    "skillVersion": "1.0",
    "generatedAt": "2026-10-08T10:35:00Z",
    "executionDuration": 285
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint"
  },
  
  "status": {
    "overall": "SUCCESS",
    "phase": "complete",
    "confidence": 0.92,
    "message": "Code generation and validation completed successfully"
  },
  
  "generation": {
    "phase": "code-generation",
    "filesGenerated": [
      {
        "path": "src/main/java/com/appverse/applications/StudentApplicationController.java",
        "type": "REST Controller",
        "lineCount": 45,
        "complexity": "medium",
        "template": "rest-controller.template"
      },
      {
        "path": "src/main/java/com/appverse/applications/StudentApplicationService.java",
        "type": "Service Class",
        "lineCount": 60,
        "complexity": "medium",
        "template": "service-class.template"
      },
      {
        "path": "src/main/java/com/appverse/applications/StudentApplicationRepository.java",
        "type": "Repository Interface",
        "lineCount": 15,
        "complexity": "low",
        "template": "repository.template"
      },
      {
        "path": "src/main/java/com/appverse/applications/StudentApplicationDTO.java",
        "type": "Data Transfer Object",
        "lineCount": 35,
        "complexity": "low",
        "template": "dto.template"
      },
      {
        "path": "src/main/java/com/appverse/applications/validation/ApplicationValidator.java",
        "type": "Validation Logic",
        "lineCount": 50,
        "complexity": "medium",
        "template": "validation.template"
      },
      {
        "path": "src/main/java/com/appverse/applications/error/ApplicationErrorHandler.java",
        "type": "Error Handler",
        "lineCount": 40,
        "complexity": "medium",
        "template": "error-handling.template"
      }
    ],
    "totalLines": 245,
    "totalClasses": 6
  },
  
  "testing": {
    "phase": "test-generation",
    "testFilesGenerated": [
      {
        "path": "src/test/java/com/appverse/applications/StudentApplicationControllerTest.java",
        "type": "Unit Test",
        "testCount": 8,
        "template": "unit-test.template"
      },
      {
        "path": "src/test/java/com/appverse/applications/StudentApplicationServiceTest.java",
        "type": "Unit Test",
        "testCount": 12,
        "template": "unit-test.template"
      },
      {
        "path": "src/test/java/com/appverse/applications/StudentApplicationIntegrationTest.java",
        "type": "Integration Test",
        "testCount": 5,
        "template": "integration-test.template"
      }
    ],
    "totalTests": 25,
    "estimatedCoverage": 94
  },
  
  "validation": {
    "phase": "syntax-validation",
    "status": "passed",
    "checks": [
      {
        "name": "Java Syntax",
        "status": "passed",
        "errors": 0,
        "warnings": 2
      },
      {
        "name": "Spring Boot Conventions",
        "status": "passed",
        "errors": 0,
        "warnings": 0
      },
      {
        "name": "Code Style (Google Java Style)",
        "status": "passed",
        "errors": 0,
        "warnings": 1
      },
      {
        "name": "Maven Dependencies",
        "status": "passed",
        "errors": 0,
        "warnings": 0
      }
    ]
  },
  
  "pullRequest": {
    "created": true,
    "prNumber": 1234,
    "title": "feat(applications): Implement application submission API endpoint (OT-18)",
    "description": "Implements POST /api/v1/applications endpoint with validation, error handling, and tenant isolation.\n\n- REST controller with validation\n- Service layer for business logic\n- Repository for data access\n- Comprehensive unit and integration tests (25 tests, 94% coverage)\n- Error handling following RFC 7807\n- Supports duplicate detection per tenant",
    "branch": "feat/OT-18-application-api",
    "targetBranch": "main",
    "url": "https://github.com/appverse/orphos/pull/1234",
    "reviewers": ["tech-lead", "backend-team"],
    "labels": ["spring-boot", "api", "feature", "auto-generated"]
  },
  
  "documentation": {
    "generated": true,
    "openApiSpec": {
      "generated": true,
      "path": "docs/openapi/applications-api.yaml",
      "endpoints": 1
    },
    "readmeUpdated": true,
    "readmePath": "applications/README.md"
  },
  
  "metrics": {
    "qualityScore": 0.92,
    "complexity": "medium",
    "maintainability": 0.88,
    "testCoverage": 0.94,
    "documentation": 0.90,
    "breakdown": {
      "codeQuality": 0.92,
      "testingQuality": 0.94,
      "documentation": 0.90,
      "architecture": 0.90
    }
  },
  
  "warnings": [
    {
      "level": "info",
      "message": "Consider adding caching for frequently accessed application data",
      "location": "StudentApplicationService.java:45"
    },
    {
      "level": "info",
      "message": "Consider adding request rate limiting to prevent abuse",
      "location": "StudentApplicationController.java:20"
    }
  ],
  
  "dependencies": {
    "newDependencies": [
      {
        "name": "spring-boot-starter-validation",
        "version": "3.1.0",
        "scope": "compile"
      }
    ],
    "updatedDependencies": []
  },
  
  "auditTrail": {
    "inputDocumentId": "spring-boot-coding-input-OT-18-20261008-103031",
    "generatedBy": "spring-boot-coding-v1",
    "generatedAt": "2026-10-08T10:35:00Z",
    "executionId": "execution-spring-boot-OT-18-20261008-103031",
    "correlationId": "task-OT-18-routing-20261008-103030"
  },
  
  "nextSteps": [
    "Review code changes in PR #1234",
    "Run automated tests in CI/CD",
    "Merge PR after review approval",
    "Deploy to staging environment",
    "Update task status in Jira"
  ]
}
```

---

### 5. Aggregated Result Document (Orchestrator → Jira)

**File:** `aggregated-result-<TASK_KEY>.json`  
**Purpose:** Final result combining all sub-skill outputs  

```json
{
  "metadata": {
    "version": "1.0",
    "format": "aggregated-result",
    "generatedAt": "2026-10-08T10:35:30Z",
    "orchestratorVersion": "1.0"
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint",
    "jiraLink": "https://jira.appverse.com/browse/OT-18"
  },
  
  "routing": {
    "decision": "task-OT-18-routing-20261008-103030",
    "skillsInvolved": [
      "spring-boot-coding"
    ],
    "executionMode": "SEQUENTIAL",
    "totalExecutionTime": 285
  },
  
  "overallStatus": {
    "status": "SUCCESS",
    "confidence": 0.92,
    "message": "All tasks completed successfully. Code generated, tested, and PR created."
  },
  
  "skillResults": [
    {
      "skillName": "spring-boot-coding",
      "skillVersion": "1.0",
      "status": "SUCCESS",
      "executionTime": 285,
      "outputDocumentId": "spring-boot-coding-output-OT-18-20261008-103500",
      "results": {
        "filesGenerated": 6,
        "testCount": 25,
        "testCoverage": 0.94,
        "prCreated": true,
        "prNumber": 1234,
        "qualityScore": 0.92
      }
    }
  ],
  
  "aggregation": {
    "phase": "merge-complete",
    "totalFilesGenerated": 6,
    "totalTestsGenerated": 25,
    "averageTestCoverage": 0.94,
    "pullRequestsCreated": 1,
    "prNumbers": [1234],
    "mergeRequired": false,
    "conflicts": []
  },
  
  "deliverables": {
    "sourcecode": {
      "files": 6,
      "lines": 245,
      "languages": ["Java"],
      "complexity": "medium"
    },
    "tests": {
      "unitTests": 20,
      "integrationTests": 5,
      "coverage": "94%"
    },
    "documentation": {
      "openApiSpec": true,
      "readmeUpdated": true,
      "inlineComments": true
    },
    "pullRequests": {
      "primary": {
        "number": 1234,
        "branch": "feat/OT-18-application-api",
        "url": "https://github.com/appverse/orphos/pull/1234",
        "reviewers": ["tech-lead", "backend-team"]
      }
    }
  },
  
  "metrics": {
    "overallQuality": 0.92,
    "codeQuality": 0.92,
    "testQuality": 0.94,
    "documentationQuality": 0.90,
    "architectureQuality": 0.90
  },
  
  "jiraUpdates": {
    "taskUpdates": [
      {
        "field": "status",
        "value": "In Progress → Code Ready",
        "updateAt": "2026-10-08T10:35:30Z"
      },
      {
        "field": "labels",
        "value": ["spring-boot-coding", "pr-#1234", "auto-generated"],
        "updateAt": "2026-10-08T10:35:30Z"
      },
      {
        "field": "description",
        "append": "## Code Generation Results\n\n- **Generated by:** Coding Orchestrator v1.0 → Spring Boot Coding Skill v1.0\n- **Files:** 6 source files, 3 test files\n- **Tests:** 25 tests (94% coverage)\n- **PR:** #1234 (ready for review)\n- **Quality Score:** 92%",
        "updateAt": "2026-10-08T10:35:30Z"
      },
      {
        "field": "comment",
        "value": "✅ Code generation completed successfully.\n\n**Deliverables:**\n- REST Controller + Service + Repository\n- 25 unit & integration tests (94% coverage)\n- OpenAPI documentation\n- PR #1234 ready for review\n\n**Next Steps:**\n1. Review PR #1234\n2. Merge after approval\n3. Update task status to \"Code Review\"",
        "updateAt": "2026-10-08T10:35:30Z"
      }
    ]
  },
  
  "auditTrail": {
    "orchestrationId": "task-OT-18-routing-20261008-103030",
    "skillExecutions": [
      {
        "skillName": "spring-boot-coding",
        "inputId": "spring-boot-coding-input-OT-18-20261008-103031",
        "outputId": "spring-boot-coding-output-OT-18-20261008-103500",
        "executionId": "execution-spring-boot-OT-18-20261008-103031",
        "startTime": "2026-10-08T10:30:31Z",
        "endTime": "2026-10-08T10:35:00Z",
        "duration": 285
      }
    ],
    "aggregatedAt": "2026-10-08T10:35:30Z",
    "aggregatedBy": "orchestrator-v1",
    "correlationId": "task-OT-18-routing-20261008-103030"
  }
}
```

---

## Orchestrator Structure

### Directory Layout

```
.claude/skills/coding-orchestrator/
├── SKILL.md                                    ← Skill documentation
├── coding-orchestrator.config.json             ← Configuration
├── coding-orchestrator.config.example.json     ← Config template
├── README.md                                   ← Setup guide
│
├── scripts/
│   ├── CodingOrchestrator.java                 ← Main orchestrator logic
│   ├── RoutingEngine.java                      ← Tag-based routing
│   ├── SkillExecutor.java                      ← Sub-skill invocation
│   ├── ResultAggregator.java                   ← Merge results
│   ├── JiraIntegration.java                    ← Jira updates
│   ├── run-coding-orchestrator.ps1             ← PowerShell launcher
│   ├── .env.example                            ← Credentials template
│   └── .gitignore
│
├── config/
│   ├── routing-map.json                        ← Tag → Skill mappings
│   ├── fallback-rules.json                     ← Fallback strategies
│   ├── skill-registry.json                     ← Available skills registry
│   └── skill-capabilities.json                 ← What each skill can do
│
├── schemas/
│   ├── task-input-schema.json                  ← JSON schema for task input
│   ├── routing-decision-schema.json            ← JSON schema for routing
│   ├── sub-skill-input-schema.json             ← JSON schema for sub-skill input
│   ├── sub-skill-output-schema.json            ← JSON schema for sub-skill output
│   └── aggregated-result-schema.json           ← JSON schema for results
│
└── templates/
    ├── jira-comment.template                   ← Template for Jira comments
    ├── pr-description.template                 ← Template for PR descriptions
    └── error-message.template                  ← Template for error messages
```

---

## Sub-Skill Structure

### Directory Layout for Spring Boot Coding Skill

```
.claude/skills/spring-boot-coding/
├── SKILL.md                                    ← Skill documentation
├── spring-boot-coding.config.json              ← Configuration
├── spring-boot-coding.config.example.json      ← Config template
├── README.md                                   ← Setup guide
│
├── scripts/
│   ├── SpringBootCodingAgent.java              ← Main skill logic
│   ├── RestControllerGenerator.java            ← REST endpoint generation
│   ├── ServiceLayerGenerator.java              ← Service class generation
│   ├── RepositoryGenerator.java                ← Repository generation
│   ├── ValidationGenerator.java                ← Validation logic
│   ├── TestGenerator.java                      ← Test code generation
│   ├── run-spring-boot-coding.ps1              ← PowerShell launcher
│   ├── .env.example                            ← Credentials template
│   └── .gitignore
│
├── templates/
│   ├── rest-controller.template                ← @RestController template
│   ├── service-class.template                  ← @Service template
│   ├── repository.template                     ← @Repository template
│   ├── dto.template                            ← Data Transfer Object
│   ├── validation.template                     ← Validation logic
│   ├── error-handler.template                  ← Error handling
│   ├── unit-test.template                      ← Unit test template
│   ├── integration-test.template               ← Integration test
│   ├── pom-snippet.template                    ← Maven dependencies
│   ├── openapi-spec.template                   ← OpenAPI documentation
│   └── README.template                         ← Module README
│
├── config/
│   ├── spring-boot-versions.json               ← Supported versions
│   ├── dependency-catalog.json                 ← Available libraries
│   └── code-style.json                         ← Code formatting rules
│
├── schemas/
│   ├── input-schema.json                       ← Input validation schema
│   ├── output-schema.json                      ← Output validation schema
│   └── validation-rules.json                   ← Business validation rules
│
└── docs/
    ├── generating-rest-endpoints.md            ← Generation guide
    ├── validation-strategies.md                ← Validation patterns
    └── error-handling-patterns.md              ← Error handling guide
```

### Similar structure for other sub-skills:
- `ui-coding/` (React, TypeScript)
- `mcp-coding/` (MCP servers)
- `database-coding/` (SQL, migrations)
- `domain-service-coding/` (DDD)
- `infrastructure-coding/` (Docker, K8s)

---

## Configuration Schemas

### Orchestrator Configuration

**File:** `coding-orchestrator.config.json`

```json
{
  "orchestrator": {
    "id": "coding-orchestrator-v1",
    "version": "1.0",
    "enabled": true
  },
  
  "routing": {
    "strategy": "tag-based",
    "fallbackStrategy": "general-coding-skill",
    "confidenceThreshold": 0.75,
    "maxSkillsPerTask": 5
  },
  
  "execution": {
    "mode": "auto-select",
    "options": ["SEQUENTIAL", "PARALLEL"],
    "parallelizeIndependentSkills": true,
    "maxConcurrentSkills": 3,
    "timeout": {
      "perSkill": 600,
      "total": 1200
    }
  },
  
  "skillRegistry": {
    "location": "./config/skill-registry.json",
    "refreshInterval": 3600,
    "discoveryMode": "static"
  },
  
  "routing": {
    "mappingLocation": "./config/routing-map.json",
    "fallbackRulesLocation": "./config/fallback-rules.json"
  },
  
  "aggregation": {
    "strategy": "merge",
    "mergeConflicts": "ask-orchestrator",
    "generateIntegratedOutput": true,
    "createSinglePullRequest": true
  },
  
  "jira": {
    "enabled": true,
    "updateStrategy": "comprehensive",
    "updateFields": [
      "status",
      "labels",
      "description",
      "comment"
    ],
    "linkPullRequests": true
  },
  
  "logging": {
    "level": "INFO",
    "logRouting": true,
    "logExecution": true,
    "logAggregation": true,
    "logAuditTrail": true
  },
  
  "formats": {
    "taskInput": {
      "schema": "./schemas/task-input-schema.json",
      "format": "json"
    },
    "routingDecision": {
      "schema": "./schemas/routing-decision-schema.json",
      "format": "json"
    },
    "subSkillInput": {
      "schema": "./schemas/sub-skill-input-schema.json",
      "format": "json"
    },
    "subSkillOutput": {
      "schema": "./schemas/sub-skill-output-schema.json",
      "format": "json"
    },
    "aggregatedResult": {
      "schema": "./schemas/aggregated-result-schema.json",
      "format": "json"
    }
  }
}
```

### Routing Map Configuration

**File:** `config/routing-map.json`

```json
{
  "version": "1.0",
  "description": "Tag to skill routing mappings",
  
  "mappings": {
    "backend": {
      "spring-boot": {
        "skill": "spring-boot-coding",
        "priority": 1,
        "confidence": 0.95,
        "isPrimary": true,
        "tags": ["api", "rest-endpoint", "rest-controller"]
      },
      "microservice": {
        "skill": "spring-boot-coding",
        "priority": 1,
        "confidence": 0.90,
        "isPrimary": true,
        "tags": ["distributed", "service-mesh"]
      },
      "domain-service": {
        "skill": "domain-service-coding",
        "priority": 1,
        "confidence": 0.90,
        "isPrimary": true,
        "tags": ["ddd", "entity", "value-object"]
      }
    },
    
    "frontend": {
      "ui": {
        "skill": "ui-coding",
        "priority": 1,
        "confidence": 0.95,
        "isPrimary": true,
        "tags": ["react", "component", "form"]
      },
      "react": {
        "skill": "ui-coding",
        "priority": 1,
        "confidence": 0.95,
        "isPrimary": true,
        "tags": ["jsx", "hooks", "state-management"]
      }
    },
    
    "integration": {
      "mcp": {
        "skill": "mcp-coding",
        "priority": 1,
        "confidence": 0.90,
        "isPrimary": true,
        "tags": ["claude-mcp", "integration"]
      }
    },
    
    "data": {
      "database": {
        "skill": "database-coding",
        "priority": 2,
        "confidence": 0.85,
        "isPrimary": false,
        "tags": ["sql", "migration", "schema"]
      }
    },
    
    "infrastructure": {
      "docker": {
        "skill": "infrastructure-coding",
        "priority": 2,
        "confidence": 0.80,
        "isPrimary": false,
        "tags": ["containerization"]
      },
      "kubernetes": {
        "skill": "infrastructure-coding",
        "priority": 2,
        "confidence": 0.80,
        "isPrimary": false,
        "tags": ["k8s", "orchestration"]
      }
    }
  },
  
  "priorities": {
    "1": "Primary - Core skill for task type",
    "2": "Secondary - Supporting skill",
    "3": "Tertiary - Optional enhancement"
  }
}
```

### Skill Registry

**File:** `config/skill-registry.json`

```json
{
  "version": "1.0",
  "lastUpdated": "2026-10-08T10:00:00Z",
  
  "skills": [
    {
      "id": "spring-boot-coding",
      "name": "Spring Boot Coding Skill",
      "version": "1.0",
      "enabled": true,
      "languages": ["Java"],
      "frameworks": ["Spring Boot", "Spring Data", "Spring Security"],
      "capabilities": [
        "rest-api-generation",
        "service-layer-generation",
        "repository-generation",
        "validation-generation",
        "test-generation"
      ],
      "inputSchema": "./../spring-boot-coding/schemas/input-schema.json",
      "outputSchema": "./../spring-boot-coding/schemas/output-schema.json",
      "scriptPath": "./../spring-boot-coding/scripts/SpringBootCodingAgent.java",
      "timeout": 600,
      "retryAttempts": 2,
      "fallback": "general-coding-skill",
      "tags": [
        "spring-boot", "api", "rest-endpoint", "validation",
        "error-handling", "testing"
      ]
    },
    {
      "id": "ui-coding",
      "name": "UI Coding Skill",
      "version": "1.0",
      "enabled": true,
      "languages": ["TypeScript", "JavaScript"],
      "frameworks": ["React", "Next.js"],
      "capabilities": [
        "component-generation",
        "form-generation",
        "state-management",
        "test-generation"
      ],
      "inputSchema": "./../ui-coding/schemas/input-schema.json",
      "outputSchema": "./../ui-coding/schemas/output-schema.json",
      "scriptPath": "./../ui-coding/scripts/UICodingAgent.java",
      "timeout": 600,
      "retryAttempts": 2,
      "fallback": "general-coding-skill",
      "tags": ["ui", "react", "component", "form", "state-management"]
    },
    {
      "id": "mcp-coding",
      "name": "MCP Server Coding Skill",
      "version": "1.0",
      "enabled": true,
      "languages": ["Java"],
      "frameworks": ["Claude MCP"],
      "capabilities": [
        "mcp-server-generation",
        "tool-generation",
        "resource-generation",
        "test-generation"
      ],
      "inputSchema": "./../mcp-coding/schemas/input-schema.json",
      "outputSchema": "./../mcp-coding/schemas/output-schema.json",
      "scriptPath": "./../mcp-coding/scripts/MCPCodingAgent.java",
      "timeout": 600,
      "retryAttempts": 2,
      "fallback": "general-coding-skill",
      "tags": ["mcp", "claude-mcp", "integration", "tools"]
    }
  ],
  
  "defaultSkill": {
    "id": "general-coding-skill",
    "name": "General Coding Skill",
    "description": "Fallback for any coding task"
  }
}
```

---

## API Contracts

### Orchestrator → Sub-Skill API

**Operation:** `invoke-skill`

**Request:**
```json
{
  "operation": "invoke-skill",
  "skill": "spring-boot-coding",
  "taskKey": "OT-18",
  "inputDocumentPath": "spring-boot-coding-input-OT-18.json",
  "timeout": 600,
  "correlationId": "task-OT-18-routing-20261008-103030"
}
```

**Response:**
```json
{
  "operation": "invoke-skill",
  "status": "success",
  "skill": "spring-boot-coding",
  "taskKey": "OT-18",
  "outputDocumentPath": "spring-boot-coding-output-OT-18.json",
  "executionTime": 285,
  "correlationId": "task-OT-18-routing-20261008-103030"
}
```

### Sub-Skill → Orchestrator API

**Standard Response Format:**
```json
{
  "metadata": {
    "skillName": "spring-boot-coding",
    "taskKey": "OT-18",
    "generatedAt": "2026-10-08T10:35:00Z"
  },
  "status": {
    "overall": "SUCCESS",
    "code": 0
  },
  "results": {
    // See Sub-Skill Output Document
  }
}
```

---

## Execution Protocols

### Sequential Execution

```
Task → Orchestrator
          ↓
       Route Task
          ↓
    Invoke Skill 1 (wait)
          ↓
       Process Output
          ↓
    Invoke Skill 2 (if needed)
          ↓
     Aggregate Results
          ↓
      Update Jira
          ↓
      Return Result
```

### Parallel Execution

```
Task → Orchestrator
          ↓
       Route Task
          ↓
    Invoke Skill 1 ─┐
    Invoke Skill 2 ─┤ (concurrent)
    Invoke Skill 3 ─┘
          ↓
    Wait for All Complete
          ↓
     Aggregate Results
          ↓
    Resolve Conflicts (if any)
          ↓
      Update Jira
          ↓
      Return Result
```

---

## Error Handling

### Error Document Format

**File:** `error-<TASK_KEY>-<TIMESTAMP>.json`

```json
{
  "metadata": {
    "version": "1.0",
    "format": "error-report",
    "generatedAt": "2026-10-08T10:35:45Z"
  },
  
  "task": {
    "key": "OT-18",
    "summary": "Implement application submission API endpoint"
  },
  
  "error": {
    "type": "SKILL_EXECUTION_FAILED",
    "severity": "ERROR",
    "code": "SPRING_BOOT_CODING_001",
    "message": "Spring Boot Coding Skill execution failed: Template validation error",
    "timestamp": "2026-10-08T10:35:30Z"
  },
  
  "context": {
    "phase": "code-generation",
    "skill": "spring-boot-coding",
    "skillVersion": "1.0",
    "stepsFailed": ["rest-controller-generation"],
    "stepsPassed": []
  },
  
  "details": {
    "originalError": "Template 'rest-controller.template' missing required field 'apiPath'",
    "stackTrace": [
      "at SpringBootCodingAgent.generate(SpringBootCodingAgent.java:145)",
      "at SkillExecutor.invokeSkill(SkillExecutor.java:78)",
      "at CodingOrchestrator.execute(CodingOrchestrator.java:234)"
    ]
  },
  
  "recovery": {
    "recommended": "RETRY_WITH_FALLBACK",
    "fallbackSkill": "general-coding-skill",
    "retryAttempts": 2,
    "retryAfter": 30
  },
  
  "jiraUpdate": {
    "status": "In Progress",
    "label": "+error-recovery",
    "comment": "❌ Code generation failed. Attempting recovery with fallback skill..."
  },
  
  "auditTrail": {
    "orchestrationId": "task-OT-18-routing-20261008-103030",
    "errorId": "error-OT-18-20261008-103530",
    "correlationId": "task-OT-18-routing-20261008-103030"
  }
}
```

---

## Summary of Document Formats

| Document | Purpose | Direction | File Format |
|----------|---------|-----------|-------------|
| Task Input | Describe what to build | Jira → Orchestrator | JSON |
| Routing Decision | Document routing logic | Orchestrator → Audit | JSON |
| Sub-Skill Input | Specify task for skill | Orchestrator → Sub-Skill | JSON |
| Sub-Skill Output | Return generated code | Sub-Skill → Orchestrator | JSON |
| Aggregated Result | Combine all results | Orchestrator → Jira | JSON |
| Error Report | Document failures | Any → Audit | JSON |

---

## Next Steps

1. **Implement Orchestrator** - Main routing and coordination engine
2. **Implement Sub-Skills** - Spring Boot, UI, MCP, Database, Infrastructure
3. **Implement Schemas** - Validate all document formats
4. **Implement Integration** - Connect to Jira and Git
5. **Test End-to-End** - Verify routing, execution, aggregation

This specification provides the complete format framework for implementing the Coding Skills Orchestrator system with all sub-skill agents.
