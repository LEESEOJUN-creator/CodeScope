// 백엔드 ApiResponse<T> 래퍼(common/response/ApiResponse.java)와 동일한 형태.
export type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  message: string | null;
};

// GithubRepositoryResponse (domain/repo/dto)와 필드 대응
export type GithubRepository = {
  id: number;
  name: string;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
  forkCount: number;
  openIssueCount: number;
  githubUrl: string;
  topics: string[];
  createdAt: string;
  updatedAt: string;
};

// Spring Data Page 직렬화 형태(GET /api/repos/search) 중 화면에서 쓰는 필드만
export type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
};

// TrendAnalysisResponse
export type TrendAnalysis = {
  repoId: number;
  fullName: string;
  analysis: string;
};

// RecommendedRepoDto
export type RecommendedRepo = {
  id: number;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
};

// RepoRecommendResponse
export type RepoRecommend = {
  stack: string;
  recommendation: string;
  candidates: RecommendedRepo[];
};

// FavoriteResponse (domain/user/dto)
export type Favorite = {
  repoId: number;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
  githubUrl: string;
  favoritedAt: string;
};

// UserResponse (GET /api/users/me)
export type UserProfile = {
  userId: number;
  username: string;
  email: string | null;
  profileImageUrl: string | null;
};

// SkillResponse (GET/POST /api/users/me/skills)
export type Skill = {
  topicId: number;
  topicName: string;
};
