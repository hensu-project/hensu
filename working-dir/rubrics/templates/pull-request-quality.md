# Rubric: GenericPRQuality

## Metadata
- name: Generic Pull Request Quality
- version: 1.0.0
- type: pr_review
- scoring: percentage
- pass_threshold: 80

## Criteria

### Description Quality (weight: 15)
#### Clear Problem Statement
- points: 5
- evaluation: PR clearly states what problem it solves

#### Solution Explanation
- points: 5
- evaluation: Explains how the solution works

#### Testing Evidence
- points: 5
- evaluation: Describes how changes were tested

### Code Changes (weight: 40)
#### Minimal Scope
- points: 10
- evaluation: Changes focused on stated problem

#### Clean Implementation
- points: 15
- evaluation: Code is readable and maintainable

#### No Breaking Changes
- points: 15
- evaluation: Backward compatibility maintained

### Testing (weight: 30)
#### Test Coverage
- points: 20
- evaluation: Adequate tests for new code

#### Edge Cases
- points: 10
- evaluation: Edge cases considered and tested

### Process (weight: 15)
#### CI Passing
- points: 10
- required: true
- evaluation: All CI checks pass

#### Review Addressed
- points: 5
- evaluation: Review comments addressed