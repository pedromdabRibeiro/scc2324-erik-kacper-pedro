# scc2324-erik-kacper

## Deadlines
- **20/October** – delivery of checkpoint – at this point, you are expected to have implemented the basic functionality + application-level caching
- **13/November** – final delivery of the project.
  
# Backend for a house rental company (short term)

## Features
- Users can make houses available for renting
- Users can rent houses
- Users can also pose questions about a house
	- A question can only be answered by the user that owns the house, and there can be only one answer for each question

## REST-API
-   User **(/rest/user)** GET/PUT/POST/DELETE: create user, delete user, update user. After a user is deleted, houses and rentals from the user appear as been performed by a default “Deleted User” user.
-   Media **(/rest/media)** GET/POST: upload media, download media.
-   House **(/rest/house)** GET/PUT/POST/DELETE: create a house, update a house (HouseAvailability), delete a house. 
-   Rental **(/rest/house/{id}/rental)** GET/PUT/POST: base URL for rental information, supporting operations for creating, updating and listing rental information.
-   Question **(/rest/house/{houseId}/question/)** POST/GET Posting a question about a house or listing all questions available for a house
-   Question **(/rest/house/{houseId}/question/{questionId})** GET - return a specific quesion with questionId
-   Answers **(/rest/house/{houseId}/question/{questionId}/answer)** POST: Replying to the question with questionId


## Models
### CosmosDB
-   **User**
	- id
	- nickname
	- name
	- (hash of the) password
	- photo-id
-   **House**
	- id
	- landlordID
	- name
	- location
	- description
	- at least one photo
	- price per day - default
	- price per day - discount
- **HouseAvailability** // From when to when the landlords want to make his house available
	- houseID
	- startdate
	- enddate
- **HouseRent** // From when to when users rent a house
	- houseID
	- tenantUserID
	- startdate
	- enddate
	- price
- **Question**
	- id
	- userID
	- houseID
	- text
- **Answer**
	- questionID
	- text


### Blob storage
- images and videos used in the system
